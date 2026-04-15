# AsiaPay Providers — Caching Proxy (Design Spec)

**Date:** 2026-04-15
**Status:** Approved for planning

## 1. Цель

Проксирующий HTTP-сервис перед `api.asiapay.asia`, решающий три задачи:

1. **Скорость/латентность** — отдавать справочник провайдеров мгновенно из кэша.
2. **Надёжность** — при недоступности апстрима продолжать отдавать последний валидный ответ (stale).
3. **Изоляция кредов** — клиенты ходят в прокси без знания Bearer-токена AsiaPay; токен хранится только в env на стороне сервиса.

## 2. Scope

**В scope (v1):** один эндпоинт — `GET /v1/tkbapp/providers`.
**Вне scope:** остальные пути AsiaPay, POST/write-запросы, мульти-тенантность, клиентская аутентификация (сервис внутренний).

## 3. Архитектура

Один Spring Boot сервис (Java 21), слои:

- **Controller** — `GET /v1/tkbapp/providers`, отдаёт сырой JSON с `Content-Type: application/json`.
- **ProvidersCacheService** — stale-while-revalidate поверх Redis.
- **AsiapayClient** — HTTP-клиент к `api.asiapay.asia` с зашитым Bearer-токеном из env.

Внешние зависимости: **Redis** (кэш + распределённый lock).

### Поток запроса

```
Client → Controller → CacheService.get()
                         ├─ fresh (age < 1h)       → return cached (hit)
                         ├─ stale (age ≥ 1h)       → return cached + async refresh
                         └─ miss (ключа нет)       → sync fetch → cache → return
                                                   → если upstream fail → 503
Async refresh:
  попытка взять Redis-lock (SET NX EX 30)
    ├─ получили → upstream call → на успех обновляем ключ, на fail — лог, stale остаётся
    └─ не получили → выходим (refresh уже идёт)
```

## 4. Кэш

- **Redis-ключ:** `asiapay:providers`
- **Значение:** JSON `{ "body": "<raw upstream json>", "fetchedAt": "<ISO-8601>", "upstreamStatus": 200 }`
- **TTL самого ключа:** отсутствует (stale бессрочно)
- **Свежесть:** вычисляется от `fetchedAt`: `age < fresh-ttl` (1h) ⇒ fresh, иначе stale
- **Lock-ключ:** `asiapay:providers:lock`, TTL 30s, берётся через `SET NX EX 30`

## 5. Компоненты (структура кода)

```
src/main/java/ru/tkb/asiapayproxy/
├── AsiapayProxyApplication.java
├── config/
│   ├── RedisConfig.java          # RedisTemplate<String, String>
│   ├── UpstreamConfig.java       # RestClient bean (baseUrl, timeout, Authorization)
│   └── AsyncConfig.java          # ThreadPoolTaskExecutor для refresh
├── controller/
│   └── ProvidersController.java
├── cache/
│   ├── CacheEntry.java           # record(String body, Instant fetchedAt, int upstreamStatus)
│   └── ProvidersCacheService.java
├── upstream/
│   └── AsiapayClient.java        # fetchProviders() → UpstreamResponse(body, status)
└── metrics/
    └── CacheMetrics.java
```

Тело ответа хранится **как сырая JSON-строка** — прокси schema-agnostic.

## 6. Конфигурация

`application.yml`:

```yaml
server:
  port: 8080
spring:
  data.redis:
    host: ${REDIS_HOST:localhost}
    port: ${REDIS_PORT:6379}
asiapay:
  base-url: https://api.asiapay.asia
  token: ${ASIAPAY_TOKEN}
  timeout-ms: 5000
cache:
  fresh-ttl: PT1H
  refresh-lock-ttl: PT30S
management:
  endpoints.web.exposure.include: health,prometheus,info
```

**Обязательные env-переменные:** `ASIAPAY_TOKEN`. `REDIS_HOST`/`REDIS_PORT` опциональны (есть дефолты).

Токен хранится только в env (compose `.env`, K8s Secret) — никогда в git.

## 7. Обработка ошибок

| Состояние кэша | Upstream          | Ответ клиенту                                    |
|----------------|-------------------|--------------------------------------------------|
| fresh          | не вызываем       | 200 из кэша                                      |
| stale          | ok                | 200 из кэша, refresh в фоне обновит              |
| stale          | fail/timeout      | 200 из кэша + лог `upstream_refresh_failed`      |
| miss           | ok                | 200 свежий, записан в Redis                      |
| miss           | fail/timeout      | **503** `{"error":"upstream_unavailable"}`       |
| miss           | 4xx               | пробрасываем статус и тело апстрима              |
| Redis down     | —                 | fail-open: ходим в upstream напрямую, warn-лог   |

## 8. Наблюдаемость

**Логи** — structured JSON (`logstash-logback-encoder`), на каждый входящий запрос:
`cache_state` (hit/stale/miss), `upstream_called` (bool), `upstream_latency_ms`, `upstream_status`.

**Метрики** (Micrometer → `/actuator/prometheus`):
- `cache_requests_total{state="hit|stale|miss"}`
- `upstream_requests_total{outcome="ok|fail|timeout"}`
- `upstream_latency_seconds` (histogram)
- `cache_refresh_failures_total`

**Health:** `/actuator/health` (Spring Boot auto-health для Redis включён).

## 9. Тестирование

- **Unit** — `ProvidersCacheService` с мокнутыми `RedisTemplate` и `AsiapayClient`. Покрывает всю матрицу из §7. Время инжектится через `Clock` бин для детерминированности.
- **Integration** — `@SpringBootTest` + Testcontainers (Redis) + WireMock (апстрим). Сценарии:
  1. Первый GET → WireMock вызван 1 раз, ответ 200.
  2. Второй GET в пределах 1h → WireMock не вызван.
  3. "Перевод часов" через `Clock` > 1h → новый GET → ответ мгновенный (stale), WireMock вызван асинхронно.
  4. WireMock отдаёт 500 при refresh → клиент всё равно получает 200 stale.
  5. Miss + WireMock down → 503.
  6. Два параллельных miss-запроса → WireMock вызван только один раз (lock работает).

## 10. Деплой

- **Dockerfile** — multi-stage: `maven:3.9-eclipse-temurin-21` (build) → `eclipse-temurin:21-jre` (runtime).
- **docker-compose.yml** — сервис + `redis:7-alpine`, `.env` для `ASIAPAY_TOKEN`.
- **.env.example** — коммитится с плейсхолдером; `.env` — в `.gitignore`.
- **README** — как запустить локально, пример Prometheus scrape-конфига.

## 11. Зависимости (`pom.xml`)

- `spring-boot-starter-web`
- `spring-boot-starter-data-redis` (+ `lettuce-core`)
- `spring-boot-starter-actuator`
- `micrometer-registry-prometheus`
- `net.logstash.logback:logstash-logback-encoder`
- Test: `spring-boot-starter-test`, `org.testcontainers:testcontainers`, `org.testcontainers:junit-jupiter`, `com.github.tomakehurst:wiremock-standalone`

## 12. Нерешённое / будущее

- Расширение на другие эндпоинты AsiaPay (сейчас только `/v1/tkbapp/providers`).
- Ручной `POST /internal/cache/purge` — пока не нужен, добавим по требованию.
- Клиентская аутентификация — не требуется, сервис в закрытой сети.
