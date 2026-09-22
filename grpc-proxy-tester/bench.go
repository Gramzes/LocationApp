package main

import (
	"context"
	"flag"
	"fmt"
	"sort"
	"sync"
	"time"

	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"

	pb "github.com/Gramzes/LocationApp/grpc-proxy-tester/testerpb"
)

func runBench(args []string) error {
	fs := flag.NewFlagSet("bench", flag.ExitOnError)
	var conn connConfig
	conn.register(fs)
	duration := fs.Duration("duration", 10*time.Second, "длительность нагрузки")
	workers := fs.Int("concurrency", 10, "число параллельных воркеров")
	size := fs.Int("size", 0, "размер payload в запросе и ответе, байт")
	delay := fs.Duration("delay", 0, "задержка ответа на сервере")
	timeout := fs.Duration("timeout", 5*time.Second, "таймаут одного вызова")
	fs.Usage = func() {
		fmt.Fprintf(fs.Output(), "Использование: %s bench [адрес] [флаги]\n\nНагружает прокси unary-вызовами и показывает RPS и задержки.\n\n", progName())
		fs.PrintDefaults()
	}
	parseArgs(fs, &conn, args)
	if *workers < 1 {
		return fmt.Errorf("--concurrency должен быть больше 0")
	}

	cc, err := conn.dial()
	if err != nil {
		return err
	}
	defer cc.Close()
	client := pb.NewTesterClient(cc)

	ctx, cancel := context.WithTimeout(conn.headers.outgoing(context.Background()), *timeout)
	err = waitReady(ctx, cc)
	cancel()
	if err != nil {
		return fmt.Errorf("не удалось подключиться: %s", describeError(err))
	}

	fmt.Printf("Нагружаю %s: %d воркеров, %s, payload %s\n", conn.describe(), *workers, *duration, humanBytes(*size))

	req := &pb.Request{Payload: makePayload(*size), ResponseSize: int32(*size), DelayMs: int32(*delay / time.Millisecond)}
	deadline := time.Now().Add(*duration)
	var (
		mu        sync.Mutex
		latencies []time.Duration
		errCodes  = map[codes.Code]int{}
		wg        sync.WaitGroup
	)
	start := time.Now()
	for w := 0; w < *workers; w++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			var local []time.Duration
			localErr := map[codes.Code]int{}
			for time.Now().Before(deadline) {
				cctx, cancel := context.WithTimeout(conn.headers.outgoing(context.Background()), *timeout)
				t0 := time.Now()
				_, err := client.Unary(cctx, req)
				cancel()
				if err != nil {
					localErr[status.Code(err)]++
					continue
				}
				local = append(local, time.Since(t0))
			}
			mu.Lock()
			latencies = append(latencies, local...)
			for c, n := range localErr {
				errCodes[c] += n
			}
			mu.Unlock()
		}()
	}
	wg.Wait()
	elapsed := time.Since(start)

	var errTotal int
	for _, n := range errCodes {
		errTotal += n
	}
	total := len(latencies) + errTotal
	fmt.Printf("\nВызовов: %d за %s (%.0f RPS), успешных: %d, ошибок: %d\n",
		total, fmtDur(elapsed), float64(total)/elapsed.Seconds(), len(latencies), errTotal)
	for c, n := range errCodes {
		fmt.Printf("  %s: %d\n", c, n)
	}
	if len(latencies) > 0 {
		sort.Slice(latencies, func(i, j int) bool { return latencies[i] < latencies[j] })
		pct := func(p float64) string {
			return fmtDur(latencies[int(float64(len(latencies)-1)*p)])
		}
		fmt.Printf("Задержка: p50 %s, p90 %s, p99 %s, max %s\n", pct(0.5), pct(0.9), pct(0.99), pct(1))
	}
	if errTotal > 0 {
		return errChecksFailed
	}
	return nil
}
