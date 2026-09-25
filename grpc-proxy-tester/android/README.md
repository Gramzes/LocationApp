# gRPC Proxy Tester для Android

Android-версия клиента `grpc-proxy-tester check`: те же 14 проверок, только
запускаются с телефона или эмулятора. Сервер по-прежнему нужен тот же —
`grpc-proxy-tester server` (см. [../README.md](../README.md)), он ставится за прокси.

```
Android-приложение  ──>  ваш прокси  ──>  grpc-proxy-tester server
```

## Сборка и запуск

1. Откройте папку `grpc-proxy-tester/android` в Android Studio (Ladybug 2024.2 или новее).
   Gradle JDK — 17 или 21 (в Android Studio по умолчанию подходит встроенный JBR).
2. Запустите конфигурацию `app` на эмуляторе или телефоне.

Из командной строки:

```bash
cd grpc-proxy-tester/android
./gradlew :app:assembleDebug        # APK: app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug         # установить на подключённое устройство
```

Готовый debug-APK собирается в GitHub Actions (workflow `grpc-proxy-tester`) —
его можно скачать со страницы запуска, раздел Artifacts → `grpc-proxy-tester-debug-apk`.

## Как пользоваться

1. Запустите тестовый сервер и прокси перед ним.
2. В приложении укажите адрес прокси:
   - из эмулятора компьютер доступен как `10.0.2.2` (например, `10.0.2.2:8080`);
   - с телефона — IP компьютера в той же сети Wi-Fi.
3. Включите TLS, если прокси слушает по TLS; для самоподписанного сертификата —
   «Не проверять сертификат».
4. Нажмите «Запустить проверки». Галочками можно выключить ненужные проверки.

Во «Дополнительно»:

| Поле                     | Что делает                                                           |
|--------------------------|----------------------------------------------------------------------|
| Заголовки                | добавляются к каждому вызову, по одному на строку: `authorization: Bearer ...` |
| `:authority`             | переопределить `:authority` (и имя для проверки сертификата)          |
| HTTP CONNECT-прокси      | идти к адресу через forward-прокси `host:port`                        |
| Таймаут, с               | таймаут одной проверки                                               |
| Долгие проверки, с       | включает `slow-unary` и `idle-stream` (0 — пропустить)                |
| Большое сообщение, КиБ   | размер для `large-message`                                           |
| Сообщений в потоке       | для потоковых проверок                                               |
| Параллельных вызовов     | для `concurrency`                                                    |

Настройки сохраняются между запусками. Кнопка «Поделиться» в заголовке отправляет
текстовый отчёт (в мессенджер, почту и т.д.).

## Запуск из скрипта

Проверки можно запустить без касаний экрана — так их гоняет сквозной тест
интерсептора (`tools/e2e.py` в GrpcInterceptor):

```bash
adb shell am start -S -n com.example.grpcproxytester/.MainActivity \
  --ez autorun true --es run_id 42 --es address 10.0.2.2:50051 \
  [--ez tls true] [--ez insecure true] [--ei long 2] [--es only unary,health] \
  [--es connect_proxy 10.0.2.2:8080]
adb logcat -d -v raw -s GrpcProxyTesterE2E:I
```

Каждое событие — одна JSON-строка в logcat с тегом `GrpcProxyTesterE2E` и
этим `run_id`: `connected`, `result` (по одной на проверку), в конце `done` или
`connect_failed`. Параметры из интента действуют только на этот прогон,
сохранённые настройки не меняются.

## Устройство проекта

- `core/` — вся логика: gRPC-клиент (grpc-java + OkHttp, grpc-kotlin), проверки,
  разбор настроек. Это обычный Kotlin/JVM-модуль без зависимостей от Android,
  код из общего `../proto/tester.proto` генерируется при сборке.
- `app/` — интерфейс на Jetpack Compose: `MainActivity`, `TesterViewModel`,
  `ui/TesterScreen.kt`.

Тесты `core` запускаются на обычной JVM против настоящего тестового сервера:

```bash
# в одном терминале
cd grpc-proxy-tester && go run . server --listen 127.0.0.1:50051

# в другом
cd grpc-proxy-tester/android
TESTER_ADDR=127.0.0.1:50051 ./gradlew :core:test
```

Дополнительно можно задать `TESTER_TLS_ADDR` (TLS-сервер, сертификат не проверяется)
и `TESTER_CONNECT_PROXY` (HTTP CONNECT-прокси к `TESTER_ADDR`). Без этих переменных
соответствующие тесты пропускаются.
