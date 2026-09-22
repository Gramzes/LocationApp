# grpc-proxy-tester

Простое консольное приложение для проверки gRPC-прокси (nginx, Envoy, HAProxy,
самописного и т.д.). Один бинарник, две роли:

```
grpc-proxy-tester check  ──>  ваш прокси  ──>  grpc-proxy-tester server
```

- `server` — тестовый gRPC-сервер, который ставится **за** прокси;
- `check` — клиент, который прогоняет через прокси набор проверок и говорит, что сломано;
- `bench` — простая нагрузка unary-вызовами (RPS, p50/p90/p99).

## Сборка

Нужен Go 1.25+.

```bash
cd grpc-proxy-tester
go build -o grpc-proxy-tester .

# под другую ОС, например Windows или Linux-сервер:
GOOS=windows GOARCH=amd64 go build -o grpc-proxy-tester.exe .
GOOS=linux   GOARCH=amd64 go build -o grpc-proxy-tester .
```

## Быстрый старт

1. Запустите тестовый сервер там, куда прокси будет проксировать запросы:

   ```bash
   ./grpc-proxy-tester server --listen :50051
   ```

2. Настройте прокси на `адрес-сервера:50051` (gRPC / HTTP/2 без TLS, или см. `--tls-cert` ниже).

3. Прогоните проверки через прокси:

   ```bash
   ./grpc-proxy-tester check localhost:8080
   ```

Пример вывода для nginx с настройками по умолчанию (`client_max_body_size 1m`) и
`grpc_read_timeout 1s`:

```
Проверяю localhost:8081 (plaintext/h2c)
  соединение установлено за 3ms

  ✓ health            935µs  SERVING
  ✓ unary               1ms  сервер "srv-1", видит клиента как 127.0.0.1:57952, authority=127.0.0.1:50051
  ✓ metadata          885µs  всё дошло, прокси не добавил своих заголовков
  ✓ error-status      702µs  NotFound с исходным текстом и трейлерами
  ✓ deadline          502ms  сервер видит дедлайн (4.999s из 5s), DeadlineExceeded через 501ms
  ✗ large-message      10ms  Unknown: unexpected HTTP status code received from server: 413 (Request Entity Too Large); ...
                             ↳ у прокси лимит на размер сообщения (например, client_max_body_size у nginx) — увеличьте его или уменьшите --large-size
  ✓ compression         3ms  gzip в обе стороны
  ✓ server-stream     905ms  10 сообщений, первое через 608µs, все за 905ms
  ✓ client-stream     957µs  10 сообщений, 5.4 KiB
  ✓ bidi-stream         3ms  10 пинг-понгов, средний RTT 303µs
  ✓ stream-error      755µs  3 сообщения, затем Aborted
  ✓ concurrency       210ms  50 вызовов по 200ms за 210ms
  ✗ slow-unary           1s  через 1s: Unavailable: unexpected HTTP status code received from server: 504 (Gateway Timeout); ...
                             ↳ прокси обрывает долгие вызовы (у Envoy таймаут маршрута по умолчанию 15s, у nginx — grpc_read_timeout 60s)
  ✗ idle-stream          2s  после паузы 2s: Internal: stream terminated by RST_STREAM with error code: INTERNAL_ERROR
                             ↳ прокси закрывает простаивающие потоки (idle timeout; у nginx — grpc_read_timeout)

Итого: пройдено 11, провалено 3, пропущено 0
```

Код выхода `check`: `0` — всё прошло, `1` — есть проваленные проверки (удобно для CI).

## Что проверяется

| Проверка        | Что проверяет                                                                     |
|-----------------|-----------------------------------------------------------------------------------|
| `health`        | стандартный `grpc.health.v1.Health/Check`                                         |
| `unary`         | обычный вызов; показывает, каким видит клиента сервер и какой пришёл `:authority` |
| `metadata`      | заголовки запроса и ответа, трейлеры, бинарные `-bin` заголовки; показывает, какие заголовки добавил прокси |
| `error-status`  | код и текст ошибки (юникод, `%`, перевод строки) и трейлеры в trailers-only ответе |
| `deadline`      | `grpc-timeout` доходит до сервера, `DeadlineExceeded` приходит вовремя            |
| `large-message` | сообщение `--large-size` (1 MiB) в обе стороны, данные сверяются по SHA-256        |
| `compression`   | сообщения, сжатые gzip                                                            |
| `server-stream` | серверный поток приходит по одному сообщению, а не пачкой (ловит буферизацию)      |
| `client-stream` | клиентский поток                                                                  |
| `bidi-stream`   | двунаправленный поток в режиме пинг-понг (ловит буферизацию запроса)              |
| `stream-error`  | статус ошибки в трейлерах после нескольких сообщений                              |
| `concurrency`   | `--concurrency` (50) параллельных вызовов на одном соединении; при нескольких серверах за прокси показывает распределение по ним |
| `slow-unary`    | вызов длительностью `--long` (таймауты прокси), только с `--long`                 |
| `idle-stream`   | поток, который молчит `--long`, а затем продолжает работу (idle timeout), только с `--long` |

Список с описаниями: `./grpc-proxy-tester check --list`.

## Флаги `check` и `bench`

Подключение (общие):

| Флаг              | Описание                                                        |
|-------------------|-----------------------------------------------------------------|
| `адрес` / `--addr`| адрес прокси, по умолчанию `localhost:50051`                    |
| `--tls`           | подключаться по TLS                                             |
| `--insecure`      | TLS без проверки сертификата                                    |
| `--ca file.pem`   | свой корневой сертификат                                        |
| `--server-name`   | имя для SNI и проверки сертификата                              |
| `--authority`     | переопределить `:authority`                                     |
| `-H "k: v"`       | дополнительный заголовок (можно несколько раз), например авторизация |
| `--max-msg-size`  | лимит размера сообщения на клиенте (по умолчанию 64 MiB)        |

Только `check`:

| Флаг              | Описание                                                        |
|-------------------|-----------------------------------------------------------------|
| `--only a,b`      | запустить только указанные проверки                             |
| `--long 30s`      | включить `slow-unary` и `idle-stream` с такой длительностью     |
| `--timeout`       | таймаут одной проверки (по умолчанию 10s)                       |
| `--large-size`    | размер большого сообщения в байтах (по умолчанию 1 MiB)         |
| `--stream-count`  | сколько сообщений в потоковых проверках (по умолчанию 10)       |
| `--concurrency`   | сколько параллельных вызовов в `concurrency` (по умолчанию 50)  |

Только `bench`: `--duration 10s`, `--concurrency 10`, `--size 0` (payload в байтах),
`--delay 0` (задержка ответа на сервере), `--timeout 5s`.

Примеры:

```bash
# прокси с TLS и авторизацией
./grpc-proxy-tester check grpc.example.com:443 --tls -H "authorization: Bearer XXX"

# самоподписанный сертификат
./grpc-proxy-tester check localhost:8443 --insecure

# проверить таймауты прокси
./grpc-proxy-tester check localhost:8080 --long 70s --only slow-unary,idle-stream

# нагрузка
./grpc-proxy-tester bench localhost:8080 --concurrency 50 --duration 30s --size 1024
```

### Forward-прокси (HTTP CONNECT)

grpc-go сам умеет ходить через HTTP CONNECT-прокси из переменной `HTTPS_PROXY`,
поэтому такой прокси проверяется без дополнительных флагов (адрес — это адрес
тестового сервера, а не прокси):

```bash
HTTPS_PROXY=http://proxy:3128 ./grpc-proxy-tester check backend:50051
```

`check` пишет, если подключение идёт через CONNECT-прокси. Адреса `localhost`/`127.0.0.1`
Go через прокси никогда не отправляет — используйте внешний IP.

## Сервер

```bash
./grpc-proxy-tester server --listen :50051 --id backend-1
```

| Флаг             | Описание                                                         |
|------------------|------------------------------------------------------------------|
| `--listen`       | адрес, по умолчанию `:50051`                                     |
| `--id`           | имя сервера в ответах (по умолчанию hostname) — удобно, если за прокси несколько серверов |
| `--tls-cert`, `--tls-key` | включить TLS (без них — plaintext / h2c)               |
| `--max-msg-size` | лимит размера сообщения (по умолчанию 64 MiB)                    |
| `--quiet`        | не логировать вызовы                                             |

Сервер логирует каждый вызов вместе с заголовками, которые до него дошли, — так
видно, что прокси добавляет, меняет или теряет:

```
127.0.0.1:57348 /proxytester.v1.Tester/Unary OK 1ms authority=127.0.0.1:50051 ua="grpc-proxy-tester grpc-go/1.84.0" x-real-ip="127.0.0.1"
```

Также на сервере включены `grpc.health.v1` и reflection, поэтому его можно дёргать
вручную через [grpcurl](https://github.com/fullstorydev/grpcurl) или Postman:

```bash
grpcurl -plaintext localhost:8080 list
grpcurl -plaintext -d '{"message": "hi", "delay_ms": 500}' localhost:8080 proxytester.v1.Tester/Unary
grpcurl -plaintext -d '{"fail_code": 5, "fail_message": "boom"}' localhost:8080 proxytester.v1.Tester/Unary
grpcurl -plaintext -d '{"count": 5, "interval_ms": 1000}' localhost:8080 proxytester.v1.Tester/ServerStream
```

Все поля запроса описаны в [`proto/tester.proto`](proto/tester.proto).

## Пример конфигурации nginx

```nginx
server {
    listen 8080 http2;
    client_max_body_size 64m;   # по умолчанию 1m — большие сообщения не пройдут
    grpc_read_timeout 300s;     # по умолчанию 60s — долгие вызовы и тихие потоки обрываются
    grpc_send_timeout 300s;

    location / {
        grpc_pass grpc://127.0.0.1:50051;
    }
}
```

## Разработка

```bash
go test ./...   # поднимает сервер и прогоняет все проверки напрямую и через TCP-прокси
```

Код в `testerpb/` сгенерирован из `proto/tester.proto`. Если меняете proto:

```bash
go install google.golang.org/protobuf/cmd/protoc-gen-go@latest
go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest
protoc -Iproto --go_out=testerpb --go_opt=paths=source_relative \
    --go-grpc_out=testerpb --go-grpc_opt=paths=source_relative proto/tester.proto
```
