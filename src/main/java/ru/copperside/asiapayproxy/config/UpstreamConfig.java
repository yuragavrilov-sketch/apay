package ru.copperside.asiapayproxy.config;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ssl.NoSuchSslBundleException;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class UpstreamConfig {

    private static final Logger log = LoggerFactory.getLogger(UpstreamConfig.class);

    @Bean
    public RestClient asiapayRestClient(AsiapayProperties props, ObjectProvider<SslBundles> sslBundlesProvider) {
        Duration timeout = Duration.ofMillis(props.timeoutMs());
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.DEFAULTS
                .withConnectTimeout(timeout)
                .withReadTimeout(timeout);

        SslBundle bundle = resolveBundle(props.sslBundle(), sslBundlesProvider.getIfAvailable());
        if (bundle != null) {
            settings = settings.withSslBundle(bundle);
            log.info("asiapay RestClient uses SSL bundle: {}", props.sslBundle());
        } else {
            log.info("asiapay RestClient uses default JVM truststore (no SSL bundle configured)");
        }

        ClientHttpRequestFactory factory = ClientHttpRequestFactories.get(settings);
        return RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .build();
    }

    private SslBundle resolveBundle(String name, SslBundles bundles) {
        if (name == null || name.isBlank() || bundles == null) {
            return null;
        }
        try {
            return bundles.getBundle(name);
        } catch (NoSuchSslBundleException e) {
            log.warn("SSL bundle '{}' not found — falling back to default truststore", name);
            return null;
        }
    }
}
