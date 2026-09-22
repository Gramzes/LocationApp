// grpc-proxy-tester — простое приложение для проверки gRPC-прокси.
//
// Схема: client (check/bench) -> проверяемый прокси -> server.
package main

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
)

const usage = `grpc-proxy-tester — проверка gRPC-прокси

Схема:  %[1]s check  ──>  ваш прокси  ──>  %[1]s server

Команды:
  server   запустить тестовый gRPC-сервер (его ставят за прокси)
  check    прогнать набор проверок через прокси
  bench    нагрузить прокси unary-вызовами и показать RPS/задержки

Примеры:
  %[1]s server --listen :50051
  %[1]s check localhost:8080
  %[1]s check proxy.example.com:443 --tls -H "authorization: Bearer XXX"
  %[1]s check localhost:8080 --long 30s
  %[1]s bench localhost:8080 --concurrency 50 --duration 30s

Флаги команды: %[1]s <команда> -h
`

// progName — имя бинарника для справки, без пути.
func progName() string { return filepath.Base(os.Args[0]) }

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintf(os.Stderr, usage, progName())
		os.Exit(2)
	}
	var err error
	switch cmd := os.Args[1]; cmd {
	case "server":
		err = runServer(os.Args[2:])
	case "check":
		err = runCheck(os.Args[2:])
	case "bench":
		err = runBench(os.Args[2:])
	case "help", "-h", "--help":
		fmt.Printf(usage, progName())
		return
	default:
		fmt.Fprintf(os.Stderr, "неизвестная команда %q\n\n"+usage, cmd, progName())
		os.Exit(2)
	}
	if err != nil {
		if !errors.Is(err, errChecksFailed) {
			fmt.Fprintln(os.Stderr, "ошибка:", err)
		}
		os.Exit(1)
	}
}
