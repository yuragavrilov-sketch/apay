# controlled-persons-registry

Сервис-воркер, который читает сообщения из Kafka, вызывает обработчик запроса и публикует финальный ответ/ошибки в выходные топики.

Ключевая точка входа обработки: [`KafkaMessageService.listen()`](src/main/java/ru/copperside/controlledpersonsregistry/service/KafkaMessageService.java:47).

## Требования

- Java 17
- Maven 3.9+
- Kafka (локально можно поднять через docker-compose, если он есть в инфраструктуре проекта)

## Профили конфигурации (local / test / prod)

Конфигурация разделена по профилям Spring:

- базовые настройки: [`application.yml`](src/main/resources/application.yml:1)
- локальные значения (без Config Server): [`application-local.yml`](src/main/resources/application-local.yml:1)
- test/prod берут конфиг из Spring Cloud Config Server:
  - [`application-test.yml`](src/main/resources/application-test.yml:1)
  - [`application-prod.yml`](src/main/resources/application-prod.yml:1)

По умолчанию выставлен профиль `local` (см. [`application.yml`](src/main/resources/application.yml:5)).

### Настройка Config Server для test/prod

Для профилей `test` и `prod` используется Spring Cloud Config Client.

- Адрес(а) конфиг-сервера задаются списком через запятую:
  - переменная окружения `SPRING_CLOUD_CONFIG_URI`
  - пример: `http://config-1:8888,http://config-2:8888`
- При недоступности первого адреса клиент попробует следующий (failover по порядку).

В репозитории конфигов должны присутствовать значения для ключей `kafka.*` (см. [`KafkaProperties`](src/main/java/ru/copperside/controlledpersonsregistry/config/KafkaProperties.java:11)).

## Kafka-топики и настройки

Основные параметры (переопределяются через local/test/prod конфиги):

- `kafka.bootstrap-servers`
- `kafka.consumer-group-id`
- `kafka.request-topic`
- `kafka.response-topic`
- `kafka.error-topic`
- `kafka.retry.*` (окно ретраев и backoff)

Сервис:

1) читает запросы из `kafka.request-topic`
2) обрабатывает через [`RequestProcessor.process()`](src/main/java/ru/copperside/controlledpersonsregistry/service/RequestProcessor.java:7)
3) публикует:
   - финальный ответ (SUCCESS/FAILED) в `kafka.response-topic`
   - события ошибок/ретраев в `kafka.error-topic`

## Метрики (Prometheus)

Метрики отдаём через Actuator endpoint:

- `GET /actuator/prometheus`

Включение endpoint-ов сделано в [`application.yml`](src/main/resources/application.yml:9).

### Прикладные метрики

Добавлены метрики Micrometer в [`KafkaMessageService`](src/main/java/ru/copperside/controlledpersonsregistry/service/KafkaMessageService.java:166):

- `controlled_persons_registry_processed_total`
  - теги: `topic`, `status`, `type`
- `controlled_persons_registry_failed_total`
  - теги: `topic`, `type`
- `controlled_persons_registry_retries_total`
  - теги: `topic`, `type`
- `controlled_persons_registry_processing_seconds`
  - теги: `topic`, `status`, `type`
  - включает percentiles/histogram (см. [`application.yml`](src/main/resources/application.yml:21))

## Запуск

### Local

Профиль `local` активен по умолчанию.

В cmd.exe:

```bat
mvn -q spring-boot:run
```

### Test / Prod

Пример запуска (test):

```bat
set SPRING_PROFILES_ACTIVE=test
set SPRING_CLOUD_CONFIG_URI=http://config-1:8888,http://config-2:8888
mvn -q spring-boot:run
```

## Примечания по коду

- Настройки Kafka собраны в один класс [`KafkaProperties`](src/main/java/ru/copperside/controlledpersonsregistry/config/KafkaProperties.java:24) (ConfigurationProperties + валидация).
- RetryTemplate создаётся в [`KafkaConfig.messageProcessingRetryTemplate()`](src/main/java/ru/copperside/controlledpersonsregistry/config/KafkaConfig.java:79) и используется в [`KafkaMessageService.listen()`](src/main/java/ru/copperside/controlledpersonsregistry/service/KafkaMessageService.java:73).

