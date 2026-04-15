package ru.tkb.asiapayproxy.config;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class UpstreamConfig {
    @Bean
    public RestClient asiapayRestClient(AsiapayProperties props) {
        SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
        rf.setConnectTimeout((int) Duration.ofMillis(props.timeoutMs()).toMillis());
        rf.setReadTimeout((int) Duration.ofMillis(props.timeoutMs()).toMillis());
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(rf)
                .build();
    }
}
