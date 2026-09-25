package main

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"runtime"
	"sort"
	"strings"
	"sync"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/encoding/gzip"
	healthpb "google.golang.org/grpc/health/grpc_health_v1"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"

	pb "github.com/Gramzes/LocationApp/grpc-proxy-tester/testerpb"
)

type checkConfig struct {
	timeout     time.Duration // таймаут одной проверки
	streamCount int           // сообщений в потоковых проверках
	largeSize   int           // размер «большого» сообщения
	concurrency int           // параллельных вызовов в проверке concurrency
	long        time.Duration // длительность для slow-unary и idle-stream (0 — пропустить)
}

type suite struct {
	conn    *grpc.ClientConn
	client  pb.TesterClient
	cfg     checkConfig
	headers headerList // заголовки из -H, чтобы не считать их добавленными прокси
}

type checkCase struct {
	name string
	desc string
	// hint — что, скорее всего, не так с прокси, если проверка упала.
	hint string
	// timeout переопределяет cfg.timeout.
	timeout func(checkConfig) time.Duration
	run     func(ctx context.Context, s *suite) (string, error)
}

type skipError string

func (e skipError) Error() string { return string(e) }

type resultKind int

const (
	passed resultKind = iota
	failed
	skipped
)

type checkResult struct {
	name   string
	kind   resultKind
	detail string
	hint   string
	dur    time.Duration
}

var checks = []checkCase{
	{
		name: "health",
		desc: "стандартный grpc.health.v1.Health/Check",
		hint: "если прокси пропускает только /proxytester.v1.Tester/*, эта проверка может падать — это нормально",
		run:  checkHealth,
	},
	{
		name: "unary",
		desc: "простой unary-вызов",
		run:  checkUnary,
	},
	{
		name: "metadata",
		desc: "заголовки запроса, заголовки ответа, трейлеры, -bin заголовки",
		hint: "прокси теряет или меняет заголовки/трейлеры (частая проблема HTTP/1.1-прокси и прокси без поддержки трейлеров)",
		run:  checkMetadata,
	},
	{
		name: "error-status",
		desc: "код и текст ошибки (grpc-status/grpc-message) + трейлеры в trailers-only ответе",
		hint: "прокси подменяет статус ошибки или теряет трейлеры в ответе без тела",
		run:  checkErrorStatus,
	},
	{
		name: "deadline",
		desc: "grpc-timeout доходит до сервера, DeadlineExceeded приходит вовремя",
		hint: "прокси не передаёт заголовок grpc-timeout на бэкенд",
		run:  checkDeadline,
	},
	{
		name: "large-message",
		desc: "большое сообщение в обе стороны без повреждений",
		hint: "у прокси лимит на размер сообщения (например, client_max_body_size у nginx) — увеличьте его или уменьшите --large-size",
		run:  checkLargeMessage,
	},
	{
		name: "compression",
		desc: "сообщения, сжатые gzip",
		hint: "прокси не пропускает grpc-encoding или ломает сжатые сообщения",
		run:  checkCompression,
	},
	{
		name: "server-stream",
		desc: "серверный поток приходит по сообщению, а не пачкой в конце",
		hint: "прокси буферизует ответ целиком (отключите буферизацию ответа)",
		timeout: func(c checkConfig) time.Duration {
			return c.timeout + time.Duration(c.streamCount)*serverStreamInterval
		},
		run: checkServerStream,
	},
	{
		name: "client-stream",
		desc: "клиентский поток",
		hint: "прокси не поддерживает клиентские потоки или буферизует запрос",
		run:  checkClientStream,
	},
	{
		name: "bidi-stream",
		desc: "двунаправленный поток в режиме пинг-понг",
		hint: "прокси ждёт окончания запроса, прежде чем отдать его бэкенду (буферизует bidi)",
		run:  checkBidiStream,
	},
	{
		name: "stream-error",
		desc: "ошибка после нескольких сообщений потока",
		hint: "прокси теряет трейлеры со статусом после тела ответа",
		run:  checkStreamError,
	},
	{
		name: "concurrency",
		desc: "много параллельных вызовов (мультиплексирование HTTP/2)",
		hint: "прокси ограничивает число одновременных потоков или обрабатывает их последовательно",
		run:  checkConcurrency,
	},
	{
		name:    "slow-unary",
		desc:    "unary-вызов, который выполняется --long времени",
		hint:    "прокси обрывает долгие вызовы (у Envoy таймаут маршрута по умолчанию 15s, у nginx — grpc_read_timeout 60s)",
		timeout: func(c checkConfig) time.Duration { return c.long + c.timeout },
		run:     checkSlowUnary,
	},
	{
		name:    "idle-stream",
		desc:    "поток, который --long времени молчит, а потом продолжает работу",
		hint:    "прокси закрывает простаивающие потоки (idle timeout; у nginx — grpc_read_timeout)",
		timeout: func(c checkConfig) time.Duration { return c.long + c.timeout },
		run:     checkIdleStream,
	},
}

func checkHealth(ctx context.Context, s *suite) (string, error) {
	resp, err := healthpb.NewHealthClient(s.conn).Check(ctx, &healthpb.HealthCheckRequest{})
	if err != nil {
		return "", err
	}
	if resp.GetStatus() != healthpb.HealthCheckResponse_SERVING {
		return "", fmt.Errorf("статус %s", resp.GetStatus())
	}
	return "SERVING", nil
}

func checkUnary(ctx context.Context, s *suite) (string, error) {
	msg := "привет, прокси!"
	resp, err := s.client.Unary(ctx, &pb.Request{Message: msg})
	if err != nil {
		return "", err
	}
	if resp.GetMessage() != msg {
		return "", fmt.Errorf("вернулось %q вместо %q", resp.GetMessage(), msg)
	}
	detail := fmt.Sprintf("сервер %q, видит клиента как %s", resp.GetServerId(), resp.GetPeer())
	if a := resp.GetReceivedMetadata()[":authority"]; a != "" {
		detail += ", authority=" + a
	}
	return detail, nil
}

// Заголовки, которые клиент gRPC отправляет сам.
var standardHeaders = map[string]bool{
	":authority":           true,
	"content-type":         true,
	"user-agent":           true,
	"grpc-accept-encoding": true,
	"grpc-encoding":        true,
	"grpc-timeout":         true,
	"te":                   true,
}

func checkMetadata(ctx context.Context, s *suite) (string, error) {
	token := randomToken()
	binVal := append([]byte{0x00, 0xff, '\n', 0x80}, []byte(token)...)
	ctx = metadata.AppendToOutgoingContext(ctx, hdrEcho, token, hdrEchoBin, string(binVal))

	var header, trailer metadata.MD
	resp, err := s.client.Unary(ctx, &pb.Request{Message: "metadata"}, grpc.Header(&header), grpc.Trailer(&trailer))
	if err != nil {
		return "", err
	}

	var problems []string
	got := resp.GetReceivedMetadata()
	if got[hdrEcho] != token {
		problems = append(problems, fmt.Sprintf("сервер получил %s=%q вместо %q", hdrEcho, got[hdrEcho], token))
	}
	if want := base64.StdEncoding.EncodeToString(binVal); got[hdrEchoBin] != want {
		problems = append(problems, "бинарный заголовок "+hdrEchoBin+" не дошёл до сервера или повреждён")
	}
	if first(header.Get(hdrServerID)) == "" {
		problems = append(problems, "нет заголовка ответа "+hdrServerID)
	}
	if v := first(header.Get(hdrEcho)); v != token {
		problems = append(problems, fmt.Sprintf("заголовок ответа %s=%q вместо %q", hdrEcho, v, token))
	}
	if v := first(trailer.Get(hdrTrailer)); v != "done" {
		problems = append(problems, "нет трейлера "+hdrTrailer)
	}
	if v := first(trailer.Get(hdrEchoBin)); v != string(binVal) {
		problems = append(problems, "бинарный трейлер "+hdrEchoBin+" не дошёл или повреждён")
	}
	if len(problems) > 0 {
		return "", errors.New(strings.Join(problems, "; "))
	}

	sent := map[string]bool{hdrEcho: true, hdrEchoBin: true}
	for _, h := range s.headers {
		sent[h.key] = true
	}
	var added []string
	for k := range got {
		if !sent[k] && !standardHeaders[k] {
			added = append(added, k)
		}
	}
	if len(added) == 0 {
		return "всё дошло, прокси не добавил своих заголовков", nil
	}
	sort.Strings(added)
	return "всё дошло; прокси добавил: " + strings.Join(added, ", "), nil
}

func checkErrorStatus(ctx context.Context, s *suite) (string, error) {
	msg := "тестовая ошибка: юникод ✓, проценты 100%, перевод\nстроки"
	var trailer metadata.MD
	_, err := s.client.Unary(ctx, &pb.Request{FailCode: int32(codes.NotFound), FailMessage: msg}, grpc.Trailer(&trailer))
	if err == nil {
		return "", errors.New("вызов завершился успешно, хотя сервер вернул ошибку")
	}
	st := status.Convert(err)
	if st.Code() != codes.NotFound {
		return "", fmt.Errorf("код %s вместо NotFound (%q)", st.Code(), st.Message())
	}
	if st.Message() != msg {
		return "", fmt.Errorf("текст ошибки искажён: %q", st.Message())
	}
	if first(trailer.Get(hdrTrailer)) != "done" {
		return "", errors.New("статус дошёл, но трейлер " + hdrTrailer + " потерян")
	}
	return "NotFound с исходным текстом и трейлерами", nil
}

func checkDeadline(ctx context.Context, s *suite) (string, error) {
	// 1. Видит ли сервер дедлайн клиента.
	const budget = 5 * time.Second
	pctx, cancel := context.WithTimeout(ctx, budget)
	resp, err := s.client.Unary(pctx, &pb.Request{Message: "deadline"})
	cancel()
	if err != nil {
		return "", err
	}
	serverSees := time.Duration(resp.GetDeadlineMs()) * time.Millisecond
	if serverSees == 0 {
		return "", errors.New("сервер не видит дедлайн клиента — grpc-timeout не дошёл")
	}
	if serverSees > budget {
		return "", fmt.Errorf("сервер видит дедлайн %s, а клиент ставил %s", serverSees, budget)
	}

	// 2. Вызов дольше дедлайна прерывается вовремя.
	const short = 500 * time.Millisecond
	dctx, cancel := context.WithTimeout(ctx, short)
	defer cancel()
	start := time.Now()
	_, err = s.client.Unary(dctx, &pb.Request{DelayMs: 3000})
	elapsed := time.Since(start)
	if err == nil {
		return "", errors.New("вызов с задержкой 3s успешно завершился при дедлайне 500ms")
	}
	if code := status.Code(err); code != codes.DeadlineExceeded {
		return "", fmt.Errorf("код %s вместо DeadlineExceeded: %s", code, status.Convert(err).Message())
	}
	if elapsed > short+time.Second {
		return "", fmt.Errorf("DeadlineExceeded пришёл только через %s", fmtDur(elapsed))
	}
	return fmt.Sprintf("сервер видит дедлайн (%s из %s), DeadlineExceeded через %s",
		serverSees.Round(time.Millisecond), budget, fmtDur(elapsed)), nil
}

func checkLargeMessage(ctx context.Context, s *suite) (string, error) {
	n := s.cfg.largeSize
	payload := make([]byte, n)
	rand.Read(payload)
	if err := unaryWithPayload(ctx, s, payload, n); err != nil {
		return "", err
	}
	return humanBytes(n) + " туда и обратно, данные целы", nil
}

func checkCompression(ctx context.Context, s *suite) (string, error) {
	payload := bytes.Repeat([]byte("сжимаемые данные "), 4096)
	if err := unaryWithPayload(ctx, s, payload, 64<<10, grpc.UseCompressor(gzip.Name)); err != nil {
		return "", err
	}
	return "gzip в обе стороны", nil
}

// unaryWithPayload отправляет payload и просит вернуть respSize байт, проверяя целостность обоих.
func unaryWithPayload(ctx context.Context, s *suite, payload []byte, respSize int, opts ...grpc.CallOption) error {
	resp, err := s.client.Unary(ctx, &pb.Request{Payload: payload, ResponseSize: int32(respSize)}, opts...)
	if err != nil {
		return err
	}
	if int(resp.GetReceivedPayloadSize()) != len(payload) {
		return fmt.Errorf("сервер получил %d байт вместо %d", resp.GetReceivedPayloadSize(), len(payload))
	}
	sum := sha256.Sum256(payload)
	if resp.GetReceivedPayloadSha256() != hex.EncodeToString(sum[:]) {
		return errors.New("данные запроса повреждены по пути к серверу")
	}
	if err := verifyPayload(resp.GetPayload(), respSize); err != nil {
		return fmt.Errorf("ответ: %w", err)
	}
	return nil
}

const serverStreamInterval = 100 * time.Millisecond

func checkServerStream(ctx context.Context, s *suite) (string, error) {
	n := s.cfg.streamCount
	const interval = serverStreamInterval
	stream, err := s.client.ServerStream(ctx, &pb.StreamRequest{
		Request:    &pb.Request{Message: "stream"},
		Count:      int32(n),
		IntervalMs: int32(interval / time.Millisecond),
	})
	if err != nil {
		return "", err
	}
	start := time.Now()
	var firstAt time.Duration
	got := 0
	for {
		resp, err := stream.Recv()
		if err == io.EOF {
			break
		}
		if err != nil {
			return "", fmt.Errorf("после %d сообщений из %d: %w", got, n, err)
		}
		got++
		if got == 1 {
			firstAt = time.Since(start)
		}
		if resp.GetSeq() != int64(got) {
			return "", fmt.Errorf("нарушен порядок: сообщение №%d пришло %d-м", resp.GetSeq(), got)
		}
	}
	total := time.Since(start)
	if got != n {
		return "", fmt.Errorf("получено %d сообщений из %d", got, n)
	}
	span := time.Duration(n-1) * interval
	if n > 2 && firstAt > span/2 {
		return "", fmt.Errorf("первое сообщение пришло через %s при длительности потока %s — ответ буферизуется",
			fmtDur(firstAt), fmtDur(total))
	}
	return fmt.Sprintf("%d сообщений, первое через %s, все за %s",
		n, fmtDur(firstAt), fmtDur(total)), nil
}

func checkClientStream(ctx context.Context, s *suite) (string, error) {
	n := s.cfg.streamCount
	stream, err := s.client.ClientStream(ctx)
	if err != nil {
		return "", err
	}
	var total int64
	for i := 1; i <= n; i++ {
		p := makePayload(i * 100)
		total += int64(len(p))
		if err := stream.Send(&pb.Request{Message: fmt.Sprintf("msg-%d", i), Payload: p}); err != nil {
			// Настоящая причина придёт из CloseAndRecv.
			break
		}
	}
	sum, err := stream.CloseAndRecv()
	if err != nil {
		return "", err
	}
	if sum.GetCount() != int64(n) || sum.GetTotalBytes() != total {
		return "", fmt.Errorf("сервер получил %d сообщений (%d байт) вместо %d (%d байт)",
			sum.GetCount(), sum.GetTotalBytes(), n, total)
	}
	if want := fmt.Sprintf("msg-%d", n); sum.GetLastMessage() != want {
		return "", fmt.Errorf("последнее сообщение %q вместо %q", sum.GetLastMessage(), want)
	}
	return fmt.Sprintf("%d сообщений, %s", n, humanBytes(int(total))), nil
}

func checkBidiStream(ctx context.Context, s *suite) (string, error) {
	n := s.cfg.streamCount
	stream, err := s.client.BidiStream(ctx)
	if err != nil {
		return "", err
	}
	var rttSum time.Duration
	for i := 1; i <= n; i++ {
		rtt, err := pingPong(stream, fmt.Sprintf("ping-%d", i), int64(i))
		if err != nil {
			return "", fmt.Errorf("сообщение %d из %d: %w", i, n, err)
		}
		rttSum += rtt
	}
	if err := stream.CloseSend(); err != nil {
		return "", err
	}
	if _, err := stream.Recv(); err != io.EOF {
		return "", fmt.Errorf("после закрытия потока ожидался EOF, получено: %v", err)
	}
	return fmt.Sprintf("%d пинг-понгов, средний RTT %s", n, fmtDur(rttSum/time.Duration(n))), nil
}

func pingPong(stream pb.Tester_BidiStreamClient, msg string, seq int64) (time.Duration, error) {
	start := time.Now()
	if err := stream.Send(&pb.Request{Message: msg}); err != nil {
		if _, rerr := stream.Recv(); rerr != nil {
			return 0, rerr
		}
		return 0, err
	}
	resp, err := stream.Recv()
	if err != nil {
		return 0, err
	}
	if resp.GetMessage() != msg || resp.GetSeq() != seq {
		return 0, fmt.Errorf("ответ %q (seq %d) вместо %q (seq %d)", resp.GetMessage(), resp.GetSeq(), msg, seq)
	}
	return time.Since(start), nil
}

func checkStreamError(ctx context.Context, s *suite) (string, error) {
	const n = 3
	msg := "ошибка после данных"
	stream, err := s.client.ServerStream(ctx, &pb.StreamRequest{
		Request: &pb.Request{Message: "x", FailCode: int32(codes.Aborted), FailMessage: msg},
		Count:   n,
	})
	if err != nil {
		return "", err
	}
	got := 0
	for {
		if _, err = stream.Recv(); err != nil {
			break
		}
		got++
	}
	if got != n {
		return "", fmt.Errorf("получено %d сообщений из %d до ошибки", got, n)
	}
	if err == io.EOF {
		return "", errors.New("поток завершился успешно, хотя сервер вернул Aborted")
	}
	st := status.Convert(err)
	if st.Code() != codes.Aborted || st.Message() != msg {
		return "", fmt.Errorf("получено %s %q вместо Aborted %q", st.Code(), st.Message(), msg)
	}
	return fmt.Sprintf("%d сообщения, затем Aborted", n), nil
}

func checkConcurrency(ctx context.Context, s *suite) (string, error) {
	n := s.cfg.concurrency
	const delay = 200 * time.Millisecond
	var (
		mu      sync.Mutex
		wg      sync.WaitGroup
		errs    []error
		servers = map[string]int{}
	)
	start := time.Now()
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			msg := fmt.Sprintf("parallel-%d", i)
			resp, err := s.client.Unary(ctx, &pb.Request{Message: msg, DelayMs: int32(delay / time.Millisecond)})
			if err == nil && resp.GetMessage() != msg {
				err = fmt.Errorf("ответ %q вместо %q", resp.GetMessage(), msg)
			}
			mu.Lock()
			defer mu.Unlock()
			if err != nil {
				errs = append(errs, err)
				return
			}
			servers[resp.GetServerId()]++
		}(i)
	}
	wg.Wait()
	elapsed := time.Since(start)
	if len(errs) > 0 {
		return "", fmt.Errorf("%d из %d вызовов с ошибкой, первая: %w", len(errs), n, errs[0])
	}
	if serial := time.Duration(n) * delay; n > 4 && elapsed > serial/4 {
		return "", fmt.Errorf("%d вызовов по %s заняли %s — они выполняются почти последовательно",
			n, delay, fmtDur(elapsed))
	}
	detail := fmt.Sprintf("%d вызовов по %s за %s", n, delay, fmtDur(elapsed))
	if len(servers) > 1 {
		ids := make([]string, 0, len(servers))
		for id, c := range servers {
			ids = append(ids, fmt.Sprintf("%s×%d", id, c))
		}
		sort.Strings(ids)
		detail += "; ответили серверы: " + strings.Join(ids, ", ")
	}
	return detail, nil
}

func checkSlowUnary(ctx context.Context, s *suite) (string, error) {
	if s.cfg.long <= 0 {
		return "", skipError("включите флагом --long, например --long 30s")
	}
	start := time.Now()
	resp, err := s.client.Unary(ctx, &pb.Request{Message: "slow", DelayMs: int32(s.cfg.long / time.Millisecond)})
	if err != nil {
		return "", fmt.Errorf("через %s: %w", fmtDur(time.Since(start)), err)
	}
	if resp.GetMessage() != "slow" {
		return "", fmt.Errorf("ответ %q вместо %q", resp.GetMessage(), "slow")
	}
	return fmt.Sprintf("ответ через %s", fmtDur(time.Since(start))), nil
}

func checkIdleStream(ctx context.Context, s *suite) (string, error) {
	if s.cfg.long <= 0 {
		return "", skipError("включите флагом --long, например --long 30s")
	}
	stream, err := s.client.BidiStream(ctx)
	if err != nil {
		return "", err
	}
	if _, err := pingPong(stream, "before-idle", 1); err != nil {
		return "", fmt.Errorf("до паузы: %w", err)
	}
	select {
	case <-time.After(s.cfg.long):
	case <-ctx.Done():
		return "", ctx.Err()
	}
	if _, err := pingPong(stream, "after-idle", 2); err != nil {
		return "", fmt.Errorf("после паузы %s: %w", s.cfg.long, err)
	}
	stream.CloseSend()
	return fmt.Sprintf("поток пережил паузу %s", s.cfg.long), nil
}

// runSuite выполняет проверки по порядку и отдаёт результат каждой в report сразу после её завершения.
func runSuite(ctx context.Context, s *suite, only map[string]bool, report func(checkResult)) []checkResult {
	var results []checkResult
	for _, c := range checks {
		if len(only) > 0 && !only[c.name] {
			continue
		}
		timeout := s.cfg.timeout
		if c.timeout != nil {
			timeout = c.timeout(s.cfg)
		}
		cctx, cancel := context.WithTimeout(s.headers.outgoing(ctx), timeout)
		start := time.Now()
		detail, err := c.run(cctx, s)
		cancel()

		r := checkResult{name: c.name, detail: detail, dur: time.Since(start)}
		var skip skipError
		switch {
		case errors.As(err, &skip):
			r.kind, r.detail = skipped, skip.Error()
		case err != nil:
			r.kind, r.detail, r.hint = failed, describeError(err), c.hint
			switch code := status.Code(err); code {
			case codes.Unimplemented, codes.Unauthenticated, codes.PermissionDenied:
				// Проблема маршрутизации/авторизации, а не того, что проверяется.
				r.hint = codeHint(code)
			default:
				if r.hint == "" {
					r.hint = codeHint(code)
				}
			}
		}
		results = append(results, r)
		report(r)
	}
	return results
}

// describeError превращает "rpc error: code = X desc = Y" в более короткое "X: Y",
// в том числе внутри обёрнутых ошибок.
// Если прокси ответил не-gRPC ответом (например, HTML-страницей 413/504),
// тело страницы в текст ошибки не попадает — остаётся только первая строка.
func describeError(err error) string {
	s, _, _ := strings.Cut(err.Error(), "\n")
	s = strings.ReplaceAll(s, "rpc error: code = ", "")
	s = strings.ReplaceAll(s, ": desc = ", ": ")
	return strings.ReplaceAll(s, " desc = ", ": ")
}

// codeHint — подсказки для кодов, которые говорят о проблеме с соединением, а не с конкретной проверкой.
func codeHint(c codes.Code) string {
	switch c {
	case codes.Unavailable:
		return "прокси недоступен, не может достучаться до бэкенда или оборвал соединение; проверьте адрес и --tls"
	case codes.Unimplemented:
		return "метод не найден — прокси не маршрутизирует этот путь на тестовый сервер"
	case codes.ResourceExhausted:
		return "превышен лимит размера сообщения или число потоков"
	case codes.Unauthenticated, codes.PermissionDenied:
		return "прокси требует авторизацию — передайте её через -H \"authorization: ...\""
	}
	return ""
}

func randomToken() string {
	b := make([]byte, 8)
	rand.Read(b)
	return hex.EncodeToString(b)
}

func first(v []string) string {
	if len(v) == 0 {
		return ""
	}
	return v[0]
}

func runCheck(args []string) error {
	fs := flag.NewFlagSet("check", flag.ExitOnError)
	var conn connConfig
	conn.register(fs)
	var cfg checkConfig
	fs.DurationVar(&cfg.timeout, "timeout", 10*time.Second, "таймаут одной проверки")
	fs.IntVar(&cfg.streamCount, "stream-count", 10, "сколько сообщений отправлять в потоковых проверках")
	fs.IntVar(&cfg.largeSize, "large-size", 1<<20, "размер большого сообщения, байт")
	fs.IntVar(&cfg.concurrency, "concurrency", 50, "сколько параллельных вызовов делать в проверке concurrency")
	fs.DurationVar(&cfg.long, "long", 0, "включить slow-unary и idle-stream с такой длительностью (например 30s)")
	onlyFlag := fs.String("only", "", "запустить только перечисленные проверки через запятую")
	list := fs.Bool("list", false, "показать список проверок и выйти")
	asJSON := fs.Bool("json", false, "вывести результат одним JSON-объектом (для автоматизации)")
	fs.Usage = func() {
		fmt.Fprintf(fs.Output(), "Использование: %s check [адрес] [флаги]\n\nПрогоняет набор проверок через прокси до тестового сервера.\n\n", progName())
		fs.PrintDefaults()
	}
	parseArgs(fs, &conn, args)

	if *list {
		for _, c := range checks {
			fmt.Printf("  %-14s %s\n", c.name, c.desc)
		}
		return nil
	}
	if cfg.streamCount < 1 || cfg.concurrency < 1 || cfg.largeSize < 0 {
		return errors.New("--stream-count и --concurrency должны быть больше 0, --large-size — не меньше 0")
	}

	only := map[string]bool{}
	if *onlyFlag != "" {
		known := map[string]bool{}
		for _, c := range checks {
			known[c.name] = true
		}
		for _, name := range strings.Split(*onlyFlag, ",") {
			name = strings.TrimSpace(name)
			if name == "" {
				continue
			}
			if !known[name] {
				return fmt.Errorf("неизвестная проверка %q (список: --list)", name)
			}
			only[name] = true
		}
	}

	cc, err := conn.dial()
	if err != nil {
		return err
	}
	defer cc.Close()
	s := &suite{conn: cc, client: pb.NewTesterClient(cc), cfg: cfg, headers: conn.headers}

	// В режиме --json человекочитаемый вывод не печатается: stdout — это ровно
	// один JSON-объект, который можно разобрать без регулярных выражений.
	var w io.Writer = os.Stdout
	out := newPrinter(os.Stdout)
	report := jsonReport{Target: conn.describe(), Results: []jsonResult{}}
	if *asJSON {
		w = io.Discard
		out = printer{w: w}
	}
	fmt.Fprintf(w, "Проверяю %s\n", conn.describe())
	if note := envProxyNote(conn.addr); note != "" && conn.connectProxy == "" {
		fmt.Fprintln(w, out.dim("  "+note))
	}

	ctx, cancel := context.WithTimeout(conn.headers.outgoing(context.Background()), cfg.timeout)
	start := time.Now()
	err = waitReady(ctx, cc)
	cancel()
	if err != nil {
		fmt.Fprintln(w, out.red("  ✗ не удалось подключиться: "+describeError(err)))
		if h := codeHint(status.Code(err)); h != "" {
			fmt.Fprintln(w, out.dim("    ↳ "+h))
		}
		if *asJSON {
			report.ConnectError = describeError(err)
			report.ConnectHint = codeHint(status.Code(err))
			writeJSON(report)
		}
		return errChecksFailed
	}
	report.Connected = true
	fmt.Fprintln(w, out.dim(fmt.Sprintf("  соединение установлено за %s", fmtDur(time.Since(start)))))
	fmt.Fprintln(w)

	results := runSuite(context.Background(), s, only, out.result)

	var p, f, sk int
	for _, r := range results {
		switch r.kind {
		case passed:
			p++
		case failed:
			f++
		case skipped:
			sk++
		}
	}
	if *asJSON {
		report.Passed, report.Failed, report.Skipped = p, f, sk
		for _, r := range results {
			report.Results = append(report.Results, jsonResult{
				Name: r.name, Status: r.kind.String(), Detail: r.detail, Hint: r.hint,
				DurationMs: float64(r.dur.Microseconds()) / 1000,
			})
		}
		writeJSON(report)
		if f > 0 {
			return errChecksFailed
		}
		return nil
	}
	fmt.Println()
	summary := fmt.Sprintf("Итого: пройдено %d, провалено %d, пропущено %d", p, f, sk)
	if f > 0 {
		fmt.Println(out.red(summary))
		return errChecksFailed
	}
	fmt.Println(out.green(summary))
	return nil
}

// jsonReport — вывод check --json. Имена полей — контракт с теми, кто разбирает
// вывод (например, tools/e2e.py в GrpcInterceptor), менять осторожно.
type jsonReport struct {
	Target       string       `json:"target"`
	Connected    bool         `json:"connected"`
	ConnectError string       `json:"connect_error,omitempty"`
	ConnectHint  string       `json:"connect_hint,omitempty"`
	Results      []jsonResult `json:"results"`
	Passed       int          `json:"passed"`
	Failed       int          `json:"failed"`
	Skipped      int          `json:"skipped"`
}

type jsonResult struct {
	Name       string  `json:"name"`
	Status     string  `json:"status"` // passed | failed | skipped
	Detail     string  `json:"detail"`
	Hint       string  `json:"hint,omitempty"`
	DurationMs float64 `json:"duration_ms"`
}

func (k resultKind) String() string {
	switch k {
	case passed:
		return "passed"
	case failed:
		return "failed"
	default:
		return "skipped"
	}
}

func writeJSON(r jsonReport) {
	enc := json.NewEncoder(os.Stdout)
	enc.SetEscapeHTML(false)
	_ = enc.Encode(r)
}

var errChecksFailed = errors.New("есть проваленные проверки")

type printer struct {
	color bool
	w     io.Writer // куда печатать результаты; nil — stdout
}

func newPrinter(f *os.File) printer {
	st, err := f.Stat()
	color := err == nil && st.Mode()&os.ModeCharDevice != 0 && os.Getenv("NO_COLOR") == ""
	// Старая консоль Windows не понимает ANSI-коды, Windows Terminal — понимает.
	if runtime.GOOS == "windows" && os.Getenv("WT_SESSION") == "" {
		color = false
	}
	return printer{color: color}
}

func (p printer) paint(code, s string) string {
	if !p.color {
		return s
	}
	return "\x1b[" + code + "m" + s + "\x1b[0m"
}

func (p printer) red(s string) string   { return p.paint("31", s) }
func (p printer) green(s string) string { return p.paint("32", s) }
func (p printer) dim(s string) string   { return p.paint("2", s) }

func (p printer) result(r checkResult) {
	var mark string
	switch r.kind {
	case passed:
		mark = p.green("✓")
	case failed:
		mark = p.red("✗")
	case skipped:
		mark = p.dim("–")
	}
	dur := ""
	if r.kind != skipped {
		dur = fmtDur(r.dur)
	}
	detail := r.detail
	switch r.kind {
	case failed:
		detail = p.red(detail)
	case skipped:
		detail = p.dim(detail)
	}
	w := p.w
	if w == nil {
		w = os.Stdout
	}
	fmt.Fprintf(w, "  %s %-14s %8s  %s\n", mark, r.name, dur, detail)
	if r.hint != "" {
		fmt.Fprintf(w, "  %s %s\n", strings.Repeat(" ", 26), p.dim("↳ "+r.hint))
	}
}
