package ru.copperside.controlledpersonsregistry.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.TimeoutRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

@Configuration
public class KafkaConfig {

    private final ControlledPersonsRegistryProperties properties;

    public KafkaConfig(ControlledPersonsRegistryProperties properties) {
        this.properties = properties;
    }

    /**
     * Оконная политика ретрая: повторяем попытки, пока не истекло заданное окно времени.
     * Используется в {@link ru.copperside.controlledpersonsregistry.service.KafkaMessageService}.
     */
    @Bean
    public RetryTemplate messageProcessingRetryTemplate() {
        TimeoutRetryPolicy retryPolicy = new TimeoutRetryPolicy();
        retryPolicy.setTimeout(properties.getKafka().getRetry().getMaxDurationMs());

        ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
        backOffPolicy.setInitialInterval(properties.getKafka().getRetry().getBackoffDelayMs());
        backOffPolicy.setMultiplier(properties.getKafka().getRetry().getBackoffMultiplier());
        // Не даём интервалу сна превысить общее окно, чтобы не "зависать" слишком надолго.
        backOffPolicy.setMaxInterval(Math.max(1L, properties.getKafka().getRetry().getMaxDurationMs()));

        RetryTemplate template = new RetryTemplate();
        template.setRetryPolicy(retryPolicy);
        template.setBackOffPolicy(backOffPolicy);
        return template;
    }
}
