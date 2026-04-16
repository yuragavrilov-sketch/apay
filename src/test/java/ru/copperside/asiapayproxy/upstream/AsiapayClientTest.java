package ru.copperside.asiapayproxy.upstream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.web.client.RestClient;
import ru.copperside.asiapayproxy.config.AsiapayProperties;

class AsiapayClientTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(com.github.tomakehurst.wiremock.core.WireMockConfiguration.options().dynamicPort())
            .build();

    private AsiapayClient clientWithToken(String token) {
        AsiapayProperties props = new AsiapayProperties(wm.baseUrl(), token, 2000, null,
                "/v1/tkbapp/providers", null);
        RestClient rc = RestClient.builder().baseUrl(props.baseUrl()).build();
        return new AsiapayClient(rc, props);
    }

    @Test
    void fetchProviders_returns200Body_andSendsBearerToken() {
        wm.stubFor(get("/v1/tkbapp/providers")
                .withHeader("Authorization", equalTo("Bearer my-token"))
                .willReturn(okJson("{\"providers\":[]}")));

        UpstreamResponse r = clientWithToken("my-token").fetchProviders();

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body()).isEqualTo("{\"providers\":[]}");
    }

    @Test
    void fetchProviders_propagates4xxStatusAndBody() {
        wm.stubFor(get("/v1/tkbapp/providers")
                .willReturn(aResponse().withStatus(401).withBody("{\"error\":\"unauthorized\"}")));

        UpstreamResponse r = clientWithToken("x").fetchProviders();

        assertThat(r.status()).isEqualTo(401);
        assertThat(r.body()).isEqualTo("{\"error\":\"unauthorized\"}");
    }

    @Test
    void fetchProviders_sendsHostHeaderWhenConfigured() {
        AsiapayProperties props = new AsiapayProperties(wm.baseUrl(), "t", 2000, null,
                "/v1/tkbapp/providers", "api.asiapay.asia");
        RestClient rc = RestClient.builder().baseUrl(props.baseUrl()).build();
        AsiapayClient c = new AsiapayClient(rc, props);

        wm.stubFor(get("/v1/tkbapp/providers")
                .withHeader("Host", equalTo("api.asiapay.asia"))
                .willReturn(okJson("{}")));

        UpstreamResponse r = c.fetchProviders();
        assertThat(r.status()).isEqualTo(200);
        wm.verify(getRequestedFor(urlEqualTo("/v1/tkbapp/providers"))
                .withHeader("Host", equalTo("api.asiapay.asia")));
    }

    @Test
    void fetchProviders_throwsUpstreamExceptionOn5xx() {
        wm.stubFor(get("/v1/tkbapp/providers").willReturn(aResponse().withStatus(500)));

        AsiapayClient client = clientWithToken("x");
        assertThatThrownBy(client::fetchProviders).isInstanceOf(UpstreamException.class);
    }
}
