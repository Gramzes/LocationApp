package main

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"testing"
	"time"

	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	pb "github.com/Gramzes/LocationApp/grpc-proxy-tester/testerpb"
)

var testCfg = checkConfig{
	timeout:     5 * time.Second,
	streamCount: 5,
	largeSize:   256 << 10,
	concurrency: 10,
	long:        300 * time.Millisecond,
}

func startServer(t *testing.T) string {
	t.Helper()
	lis, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	srv := newGRPCServer("test-server", 64<<20, false)
	go srv.Serve(lis)
	t.Cleanup(srv.Stop)
	return lis.Addr().String()
}

// startTCPProxy — простейший L4-прокси, чтобы прогнать проверки не напрямую.
func startTCPProxy(t *testing.T, backend string) string {
	t.Helper()
	lis, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { lis.Close() })
	go func() {
		for {
			in, err := lis.Accept()
			if err != nil {
				return
			}
			go func() {
				defer in.Close()
				out, err := net.Dial("tcp", backend)
				if err != nil {
					return
				}
				defer out.Close()
				go io.Copy(out, in)
				io.Copy(in, out)
			}()
		}
	}()
	return lis.Addr().String()
}

// startConnectProxy — простейший HTTP CONNECT-прокси; запоминает, куда просили туннели.
func startConnectProxy(t *testing.T) (string, <-chan string) {
	t.Helper()
	lis, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { lis.Close() })
	targets := make(chan string, 100)
	go func() {
		for {
			in, err := lis.Accept()
			if err != nil {
				return
			}
			go func() {
				defer in.Close()
				br := bufio.NewReader(in)
				req, err := http.ReadRequest(br)
				if err != nil || req.Method != http.MethodConnect {
					return
				}
				targets <- req.Host
				out, err := net.Dial("tcp", req.Host)
				if err != nil {
					io.WriteString(in, "HTTP/1.1 502 Bad Gateway\r\n\r\n")
					return
				}
				defer out.Close()
				io.WriteString(in, "HTTP/1.1 200 Connection established\r\n\r\n")
				go io.Copy(out, br)
				io.Copy(in, out)
			}()
		}
	}()
	return lis.Addr().String(), targets
}

func runAll(t *testing.T, addr string, headers headerList) []checkResult {
	t.Helper()
	return runAllVia(t, connConfig{addr: addr, maxMsgSize: 64 << 20, headers: headers})
}

func runAllVia(t *testing.T, conn connConfig) []checkResult {
	t.Helper()
	headers := conn.headers
	cc, err := conn.dial()
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { cc.Close() })
	s := &suite{conn: cc, client: pb.NewTesterClient(cc), cfg: testCfg, headers: headers}
	return runSuite(context.Background(), s, nil, func(checkResult) {})
}

func assertAllPassed(t *testing.T, results []checkResult) {
	t.Helper()
	if len(results) != len(checks) {
		t.Fatalf("выполнено %d проверок из %d", len(results), len(checks))
	}
	for _, r := range results {
		if r.kind != passed {
			t.Errorf("%s: %s", r.name, r.detail)
		}
	}
}

func TestChecksDirect(t *testing.T) {
	assertAllPassed(t, runAll(t, startServer(t), nil))
}

func TestChecksThroughTCPProxy(t *testing.T) {
	proxy := startTCPProxy(t, startServer(t))
	assertAllPassed(t, runAll(t, proxy, headerList{{"authorization", "Bearer test"}}))
}

func TestChecksThroughConnectProxy(t *testing.T) {
	// 127.0.0.1 grpc-go через HTTPS_PROXY не пускает никогда; --connect-proxy — пускает.
	backend := startServer(t)
	proxy, targets := startConnectProxy(t)
	assertAllPassed(t, runAllVia(t, connConfig{addr: backend, maxMsgSize: 64 << 20, connectProxy: proxy}))
	select {
	case got := <-targets:
		if got != backend {
			t.Fatalf("туннель к %s, ожидался %s", got, backend)
		}
	default:
		t.Fatal("соединение шло мимо CONNECT-прокси")
	}
}

func TestWaitReadyUnreachable(t *testing.T) {
	lis, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := lis.Addr().String()
	lis.Close() // порт свободен — соединение будет отвергнуто

	conn := connConfig{addr: addr, maxMsgSize: 1 << 20}
	cc, err := conn.dial()
	if err != nil {
		t.Fatal(err)
	}
	defer cc.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	err = waitReady(ctx, cc)
	if status.Code(err) != codes.Unavailable {
		t.Fatalf("ожидался Unavailable, получено %v", err)
	}
}

func TestHeaderListSet(t *testing.T) {
	var h headerList
	for _, s := range []string{"Authorization: Bearer a:b", "x-foo=bar"} {
		if err := h.Set(s); err != nil {
			t.Fatal(err)
		}
	}
	want := headerList{{"authorization", "Bearer a:b"}, {"x-foo", "bar"}}
	if len(h) != len(want) || h[0] != want[0] || h[1] != want[1] {
		t.Fatalf("получено %v", h)
	}
	if err := h.Set("без разделителя"); err == nil {
		t.Fatal("ожидалась ошибка")
	}
}

func TestDescribeError(t *testing.T) {
	err := status.Error(codes.Unavailable, "connection error: desc = \"refused\"\n<html>")
	got := describeError(fmt.Errorf("после паузы: %w", err))
	want := "после паузы: Unavailable: connection error: \"refused\""
	if got != want {
		t.Fatalf("получено %q, ожидалось %q", got, want)
	}
}
