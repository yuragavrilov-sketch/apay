package ru.tkb.asiapayproxy.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class AsyncConfig {
    @Bean(name = "refreshExecutor")
    public Executor refreshExecutor() {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        ex.setCorePoolSize(2);
        ex.setMaxPoolSize(2);
        ex.setQueueCapacity(10);
        ex.setThreadNamePrefix("refresh-");
        ex.initialize();
        return ex;
    }
}
