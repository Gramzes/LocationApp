package main

import (
	"context"
	"crypto/sha256"
	"crypto/tls"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"os/signal"
	"sort"
	"strings"
	"syscall"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials"
	_ "google.golang.org/grpc/encoding/gzip" // сервер умеет принимать и отдавать gzip
	"google.golang.org/grpc/health"
	healthpb "google.golang.org/grpc/health/grpc_health_v1"
	"google.golang.org/grpc/keepalive"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/peer"
	"google.golang.org/grpc/reflection"
	"google.golang.org/grpc/status"

	pb "github.com/Gramzes/LocationApp/grpc-proxy-tester/testerpb"
)

// Служебные заголовки, которыми обмениваются клиент и сервер во время проверок.
const (
	hdrServerID = "x-tester-server-id" // сервер -> клиент, в заголовках ответа
	hdrEcho     = "x-tester-echo"      // клиент -> сервер -> клиент, в заголовках ответа
	hdrEchoBin  = "x-tester-echo-bin"  // клиент -> сервер -> клиент, в трейлерах
	hdrTrailer  = "x-tester-trailer"   // сервер -> клиент, в трейлерах
)

type testerServer struct {
	pb.UnimplementedTesterServer
	id         string
	maxMsgSize int
}

func (s *testerServer) Unary(ctx context.Context, req *pb.Request) (*pb.Response, error) {
	in, _ := metadata.FromIncomingContext(ctx)
	grpc.SetHeader(ctx, responseHeader(in, s.id))
	grpc.SetTrailer(ctx, responseTrailer(in))

	if err := s.handle(ctx, req); err != nil {
		return nil, err
	}
	return s.response(ctx, req, 1), nil
}

func (s *testerServer) ServerStream(req *pb.StreamRequest, stream pb.Tester_ServerStreamServer) error {
	ctx := stream.Context()
	in, _ := metadata.FromIncomingContext(ctx)
	stream.SetHeader(responseHeader(in, s.id))
	stream.SetTrailer(responseTrailer(in))

	tmpl := req.GetRequest()
	if tmpl == nil {
		tmpl = &pb.Request{}
	}
	if int(tmpl.GetResponseSize()) > s.maxMsgSize {
		return status.Errorf(codes.InvalidArgument, "response_size больше %d", s.maxMsgSize)
	}
	interval := time.Duration(req.GetIntervalMs()) * time.Millisecond
	for i := int64(1); i <= int64(req.GetCount()); i++ {
		if i > 1 {
			if err := sleepCtx(ctx, interval); err != nil {
				return err
			}
		}
		if err := stream.Send(s.response(ctx, tmpl, i)); err != nil {
			return err
		}
	}
	// Ошибка (если её попросили) возвращается уже после всех сообщений:
	// так проверяется передача статуса в трейлерах после данных.
	return failure(tmpl)
}

func (s *testerServer) ClientStream(stream pb.Tester_ClientStreamServer) error {
	ctx := stream.Context()
	in, _ := metadata.FromIncomingContext(ctx)
	stream.SetHeader(responseHeader(in, s.id))
	stream.SetTrailer(responseTrailer(in))

	sum := &pb.Summary{ServerId: s.id, ReceivedMetadata: flattenMetadata(in)}
	for {
		req, err := stream.Recv()
		if err == io.EOF {
			return stream.SendAndClose(sum)
		}
		if err != nil {
			return err
		}
		if err := failure(req); err != nil {
			return err
		}
		sum.Count++
		sum.TotalBytes += int64(len(req.GetPayload()))
		sum.LastMessage = req.GetMessage()
	}
}

func (s *testerServer) BidiStream(stream pb.Tester_BidiStreamServer) error {
	ctx := stream.Context()
	in, _ := metadata.FromIncomingContext(ctx)
	stream.SetHeader(responseHeader(in, s.id))
	stream.SetTrailer(responseTrailer(in))

	for seq := int64(1); ; seq++ {
		req, err := stream.Recv()
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return err
		}
		if err := s.handle(ctx, req); err != nil {
			return err
		}
		if err := stream.Send(s.response(ctx, req, seq)); err != nil {
			return err
		}
	}
}

// handle выполняет задержку и возвращает ошибку, если их попросили в запросе.
func (s *testerServer) handle(ctx context.Context, req *pb.Request) error {
	if int(req.GetResponseSize()) > s.maxMsgSize {
		return status.Errorf(codes.InvalidArgument, "response_size больше %d", s.maxMsgSize)
	}
	if err := sleepCtx(ctx, time.Duration(req.GetDelayMs())*time.Millisecond); err != nil {
		return err
	}
	return failure(req)
}

func (s *testerServer) response(ctx context.Context, req *pb.Request, seq int64) *pb.Response {
	in, _ := metadata.FromIncomingContext(ctx)
	sum := sha256.Sum256(req.GetPayload())
	resp := &pb.Response{
		Message:               req.GetMessage(),
		Payload:               makePayload(int(req.GetResponseSize())),
		Seq:                   seq,
		ServerId:              s.id,
		ReceivedMetadata:      flattenMetadata(in),
		ReceivedPayloadSize:   int32(len(req.GetPayload())),
		ReceivedPayloadSha256: hex.EncodeToString(sum[:]),
	}
	if p, ok := peer.FromContext(ctx); ok {
		resp.Peer = p.Addr.String()
	}
	if d, ok := ctx.Deadline(); ok {
		resp.DeadlineMs = max(time.Until(d).Milliseconds(), 1)
	}
	return resp
}

func failure(req *pb.Request) error {
	if req.GetFailCode() == 0 {
		return nil
	}
	return status.Error(codes.Code(req.GetFailCode()), req.GetFailMessage())
}

func sleepCtx(ctx context.Context, d time.Duration) error {
	if d <= 0 {
		return nil
	}
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-t.C:
		return nil
	case <-ctx.Done():
		return status.FromContextError(ctx.Err()).Err()
	}
}

func responseHeader(in metadata.MD, id string) metadata.MD {
	md := metadata.Pairs(hdrServerID, id)
	if v := in.Get(hdrEcho); len(v) > 0 {
		md.Set(hdrEcho, v...)
	}
	return md
}

func responseTrailer(in metadata.MD) metadata.MD {
	md := metadata.Pairs(hdrTrailer, "done")
	if v := in.Get(hdrEchoBin); len(v) > 0 {
		md.Set(hdrEchoBin, v...)
	}
	return md
}

// flattenMetadata превращает входящие заголовки в map для ответа.
// Бинарные (-bin) значения кодируются в base64, чтобы оставаться валидным UTF-8.
func flattenMetadata(md metadata.MD) map[string]string {
	out := make(map[string]string, len(md))
	for k, vals := range md {
		conv := make([]string, len(vals))
		for i, v := range vals {
			if strings.HasSuffix(k, "-bin") {
				conv[i] = base64.StdEncoding.EncodeToString([]byte(v))
			} else {
				conv[i] = strings.ToValidUTF8(v, "?")
			}
		}
		out[k] = strings.Join(conv, ", ")
	}
	return out
}

// Заголовки, которые есть в любом gRPC-запросе и не интересны в логе.
var boringHeaders = map[string]bool{
	":authority":           true,
	"content-type":         true,
	"user-agent":           true,
	"grpc-accept-encoding": true,
	"te":                   true,
}

func logCall(ctx context.Context, method string, start time.Time, err error) {
	var b strings.Builder
	addr := "?"
	if p, ok := peer.FromContext(ctx); ok {
		addr = p.Addr.String()
	}
	fmt.Fprintf(&b, "%s %s %s %s", addr, method, status.Code(err), fmtDur(time.Since(start)))
	md, _ := metadata.FromIncomingContext(ctx)
	if v := md.Get(":authority"); len(v) > 0 {
		fmt.Fprintf(&b, " authority=%s", v[0])
	}
	if v := md.Get("user-agent"); len(v) > 0 {
		fmt.Fprintf(&b, " ua=%q", v[0])
	}
	flat := flattenMetadata(md)
	keys := make([]string, 0, len(flat))
	for k := range flat {
		if !boringHeaders[k] {
			keys = append(keys, k)
		}
	}
	sort.Strings(keys)
	for _, k := range keys {
		fmt.Fprintf(&b, " %s=%q", k, flat[k])
	}
	if err != nil {
		fmt.Fprintf(&b, " err=%q", status.Convert(err).Message())
	}
	log.Print(b.String())
}

func newGRPCServer(id string, maxMsgSize int, verbose bool, extra ...grpc.ServerOption) *grpc.Server {
	opts := []grpc.ServerOption{
		grpc.MaxRecvMsgSize(maxMsgSize),
		grpc.MaxSendMsgSize(maxMsgSize),
		// Не рвём соединение, если прокси часто шлёт keepalive-пинги.
		grpc.KeepaliveEnforcementPolicy(keepalive.EnforcementPolicy{
			MinTime:             5 * time.Second,
			PermitWithoutStream: true,
		}),
	}
	if verbose {
		opts = append(opts,
			grpc.ChainUnaryInterceptor(func(ctx context.Context, req any, info *grpc.UnaryServerInfo, h grpc.UnaryHandler) (any, error) {
				start := time.Now()
				resp, err := h(ctx, req)
				logCall(ctx, info.FullMethod, start, err)
				return resp, err
			}),
			grpc.ChainStreamInterceptor(func(srv any, ss grpc.ServerStream, info *grpc.StreamServerInfo, h grpc.StreamHandler) error {
				start := time.Now()
				err := h(srv, ss)
				logCall(ss.Context(), info.FullMethod, start, err)
				return err
			}),
		)
	}
	s := grpc.NewServer(append(opts, extra...)...)
	pb.RegisterTesterServer(s, &testerServer{id: id, maxMsgSize: maxMsgSize})

	hs := health.NewServer()
	hs.SetServingStatus("", healthpb.HealthCheckResponse_SERVING)
	hs.SetServingStatus(pb.Tester_ServiceDesc.ServiceName, healthpb.HealthCheckResponse_SERVING)
	healthpb.RegisterHealthServer(s, hs)

	// Reflection позволяет дёргать сервер через grpcurl / Postman без .proto файла.
	reflection.Register(s)
	return s
}

func runServer(args []string) error {
	hostname, _ := os.Hostname()
	fs := flag.NewFlagSet("server", flag.ExitOnError)
	listen := fs.String("listen", ":50051", "адрес, на котором слушать")
	id := fs.String("id", hostname, "идентификатор сервера (возвращается в ответах)")
	certFile := fs.String("tls-cert", "", "PEM-сертификат для TLS (без него — plaintext/h2c)")
	keyFile := fs.String("tls-key", "", "PEM-ключ для TLS")
	maxMsg := fs.Int("max-msg-size", 64<<20, "максимальный размер сообщения, байт")
	quiet := fs.Bool("quiet", false, "не логировать каждый вызов")
	fs.Usage = func() {
		fmt.Fprintf(fs.Output(), "Использование: %s server [флаги]\n\nЗапускает тестовый gRPC-сервер, который ставится за проверяемым прокси.\n\n", progName())
		fs.PrintDefaults()
	}
	fs.Parse(args)

	var extra []grpc.ServerOption
	mode := "plaintext (h2c)"
	if *certFile != "" || *keyFile != "" {
		cert, err := tls.LoadX509KeyPair(*certFile, *keyFile)
		if err != nil {
			return fmt.Errorf("загрузка сертификата: %w", err)
		}
		extra = append(extra, grpc.Creds(credentials.NewTLS(&tls.Config{Certificates: []tls.Certificate{cert}})))
		mode = "TLS"
	}

	lis, err := net.Listen("tcp", *listen)
	if err != nil {
		return err
	}
	srv := newGRPCServer(*id, *maxMsg, !*quiet, extra...)

	go func() {
		sig := make(chan os.Signal, 1)
		signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
		<-sig
		log.Print("останавливаюсь...")
		srv.GracefulStop()
	}()

	log.Printf("тестовый gRPC-сервер %q слушает %s, %s", *id, lis.Addr(), mode)
	if err := srv.Serve(lis); err != nil && !errors.Is(err, grpc.ErrServerStopped) {
		return err
	}
	return nil
}
