package main

import (
	"bufio"
	"context"
	"crypto/tls"
	"crypto/x509"
	"flag"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/connectivity"
	"google.golang.org/grpc/credentials"
	"google.golang.org/grpc/credentials/insecure"
	healthpb "google.golang.org/grpc/health/grpc_health_v1"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

// connConfig — параметры подключения к проверяемому прокси (общие для check и bench).
type connConfig struct {
	addr       string
	useTLS     bool
	skipVerify bool
	caFile     string
	serverName string
	authority  string
	headers    headerList
	maxMsgSize int
	// connectProxy — host:port HTTP CONNECT-прокси. В отличие от HTTPS_PROXY,
	// работает и для адресов вроде 127.0.0.1, которые grpc-go мимо прокси
	// из окружения всегда отправляет напрямую.
	connectProxy string
}

func (c *connConfig) register(fs *flag.FlagSet) {
	fs.StringVar(&c.addr, "addr", "localhost:50051", "адрес прокси (можно передать первым аргументом)")
	fs.BoolVar(&c.useTLS, "tls", false, "подключаться по TLS")
	fs.BoolVar(&c.skipVerify, "insecure", false, "TLS без проверки сертификата (включает --tls)")
	fs.StringVar(&c.caFile, "ca", "", "PEM с корневым сертификатом для проверки TLS (включает --tls)")
	fs.StringVar(&c.serverName, "server-name", "", "имя сервера для TLS (SNI и проверка сертификата, включает --tls)")
	fs.StringVar(&c.authority, "authority", "", "переопределить заголовок :authority")
	fs.Var(&c.headers, "H", "дополнительный заголовок `\"ключ: значение\"` для каждого вызова (можно несколько раз)")
	fs.IntVar(&c.maxMsgSize, "max-msg-size", 64<<20, "максимальный размер сообщения на клиенте, байт")
	fs.StringVar(&c.connectProxy, "connect-proxy", "", "идти к адресу через HTTP CONNECT-прокси `host:port` (вместо HTTPS_PROXY)")
}

// parseArgs разбирает флаги и позволяет указать адрес позиционным аргументом
// в любом месте: `check proxy:443 --tls` или `check --tls proxy:443`.
func parseArgs(fs *flag.FlagSet, c *connConfig, args []string) {
	fs.Parse(args)
	for fs.NArg() > 0 {
		c.addr = fs.Arg(0)
		fs.Parse(fs.Args()[1:])
	}
	if c.skipVerify || c.caFile != "" || c.serverName != "" {
		c.useTLS = true
	}
}

func (c *connConfig) dial() (*grpc.ClientConn, error) {
	creds := insecure.NewCredentials()
	if c.useTLS {
		cfg := &tls.Config{ServerName: c.serverName, InsecureSkipVerify: c.skipVerify}
		if c.caFile != "" {
			pem, err := os.ReadFile(c.caFile)
			if err != nil {
				return nil, err
			}
			cfg.RootCAs = x509.NewCertPool()
			if !cfg.RootCAs.AppendCertsFromPEM(pem) {
				return nil, fmt.Errorf("в %s не найдено ни одного сертификата", c.caFile)
			}
		}
		creds = credentials.NewTLS(cfg)
	}
	opts := []grpc.DialOption{
		grpc.WithTransportCredentials(creds),
		grpc.WithUserAgent("grpc-proxy-tester"),
		grpc.WithDefaultCallOptions(
			grpc.MaxCallRecvMsgSize(c.maxMsgSize),
			grpc.MaxCallSendMsgSize(c.maxMsgSize),
		),
	}
	if c.authority != "" {
		opts = append(opts, grpc.WithAuthority(c.authority))
	}
	if c.connectProxy != "" {
		proxy := c.connectProxy
		opts = append(opts, grpc.WithNoProxy(), grpc.WithContextDialer(func(ctx context.Context, addr string) (net.Conn, error) {
			return dialConnect(ctx, proxy, addr)
		}))
	}
	return grpc.NewClient(c.addr, opts...)
}

// dialConnect открывает туннель к addr через HTTP CONNECT-прокси.
func dialConnect(ctx context.Context, proxy, addr string) (net.Conn, error) {
	var d net.Dialer
	conn, err := d.DialContext(ctx, "tcp", proxy)
	if err != nil {
		return nil, fmt.Errorf("CONNECT-прокси %s: %w", proxy, err)
	}
	if dl, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(dl)
	}
	req := &http.Request{Method: http.MethodConnect, URL: &url.URL{Opaque: addr}, Host: addr, Header: http.Header{}}
	req.Header.Set("User-Agent", "grpc-proxy-tester")
	if err := req.Write(conn); err != nil {
		conn.Close()
		return nil, fmt.Errorf("CONNECT-прокси %s: %w", proxy, err)
	}
	br := bufio.NewReader(conn)
	resp, err := http.ReadResponse(br, req)
	if err != nil {
		conn.Close()
		return nil, fmt.Errorf("CONNECT-прокси %s: %w", proxy, err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		conn.Close()
		return nil, fmt.Errorf("CONNECT-прокси %s ответил %s", proxy, resp.Status)
	}
	_ = conn.SetDeadline(time.Time{})
	if br.Buffered() > 0 {
		// Сервер (например, h2) мог начать говорить сразу после ответа прокси.
		return &bufferedConn{Conn: conn, r: br}, nil
	}
	return conn, nil
}

type bufferedConn struct {
	net.Conn
	r *bufio.Reader
}

func (b *bufferedConn) Read(p []byte) (int, error) { return b.r.Read(p) }

func (c *connConfig) describe() string {
	var parts []string
	if c.useTLS {
		s := "TLS"
		if c.skipVerify {
			s += " без проверки сертификата"
		}
		parts = append(parts, s)
	} else {
		parts = append(parts, "plaintext/h2c")
	}
	if c.serverName != "" {
		parts = append(parts, "server-name="+c.serverName)
	}
	if c.authority != "" {
		parts = append(parts, "authority="+c.authority)
	}
	if len(c.headers) > 0 {
		parts = append(parts, fmt.Sprintf("доп. заголовков: %d", len(c.headers)))
	}
	if c.connectProxy != "" {
		parts = append(parts, "через CONNECT "+c.connectProxy)
	}
	return fmt.Sprintf("%s (%s)", c.addr, strings.Join(parts, ", "))
}

// waitReady устанавливает соединение с прокси заранее, чтобы при проблемах
// с сетью/TLS/HTTP2 показать одну понятную ошибку вместо провала всех проверок.
func waitReady(ctx context.Context, cc *grpc.ClientConn) error {
	cc.Connect()
	for {
		st := cc.GetState()
		switch st {
		case connectivity.Ready:
			return nil
		case connectivity.TransientFailure:
			// Вызов без WaitForReady сразу вернёт последнюю ошибку соединения.
			_, err := healthpb.NewHealthClient(cc).Check(ctx, &healthpb.HealthCheckRequest{})
			if status.Code(err) == codes.Unavailable {
				return err
			}
		}
		if !cc.WaitForStateChange(ctx, st) {
			return fmt.Errorf("соединение не установлено за отведённое время (состояние %s)", st)
		}
	}
}

// envProxyNote сообщает, если grpc-go пойдёт к addr через HTTP CONNECT прокси
// из HTTPS_PROXY (grpc-go определяет это так же, через http.ProxyFromEnvironment).
func envProxyNote(addr string) string {
	u, err := http.ProxyFromEnvironment(&http.Request{URL: &url.URL{Scheme: "https", Host: addr}})
	if err != nil || u == nil {
		return ""
	}
	return fmt.Sprintf("подключение идёт через HTTP CONNECT-прокси %s (из HTTPS_PROXY)", u.Redacted())
}

type header struct{ key, value string }

type headerList []header

func (h *headerList) String() string {
	parts := make([]string, len(*h))
	for i, x := range *h {
		parts[i] = x.key + ": " + x.value
	}
	return strings.Join(parts, ", ")
}

// outgoing добавляет заголовки к исходящему контексту.
func (h headerList) outgoing(ctx context.Context) context.Context {
	for _, x := range h {
		ctx = metadata.AppendToOutgoingContext(ctx, x.key, x.value)
	}
	return ctx
}

func (h *headerList) Set(s string) error {
	sep := strings.IndexAny(s, ":=")
	if sep <= 0 {
		return fmt.Errorf("ожидается \"ключ: значение\", получено %q", s)
	}
	key := strings.ToLower(strings.TrimSpace(s[:sep]))
	*h = append(*h, header{key: key, value: strings.TrimSpace(s[sep+1:])})
	return nil
}

// makePayload возвращает n байт с предсказуемым содержимым, которое клиент может проверить.
func makePayload(n int) []byte {
	if n <= 0 {
		return nil
	}
	b := make([]byte, n)
	for i := range b {
		b[i] = byte(i % 251)
	}
	return b
}

func verifyPayload(b []byte, n int) error {
	if len(b) != n {
		return fmt.Errorf("получено %d байт вместо %d", len(b), n)
	}
	for i, v := range b {
		if v != byte(i%251) {
			return fmt.Errorf("данные повреждены начиная с байта %d", i)
		}
	}
	return nil
}

// fmtDur округляет длительность для вывода, не превращая доли миллисекунды в "0s".
func fmtDur(d time.Duration) string {
	switch {
	case d < time.Millisecond:
		return d.Round(time.Microsecond).String()
	case d < time.Second:
		return d.Round(time.Millisecond).String()
	default:
		return d.Round(10 * time.Millisecond).String()
	}
}

func humanBytes(n int) string {
	switch {
	case n >= 1<<20 && n%(1<<20) == 0:
		return fmt.Sprintf("%d MiB", n>>20)
	case n >= 1<<20:
		return fmt.Sprintf("%.1f MiB", float64(n)/(1<<20))
	case n >= 1<<10:
		return fmt.Sprintf("%.1f KiB", float64(n)/(1<<10))
	default:
		return fmt.Sprintf("%d B", n)
	}
}
