package ru.copperside.asiapayproxy.upstream;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import ru.copperside.asiapayproxy.config.AsiapayProperties;

@Component
public class AsiapayClient {

    private static final String PATH = "/v1/tkbapp/providers";

    private final RestClient restClient;
    private final AsiapayProperties props;

    public AsiapayClient(RestClient asiapayRestClient, AsiapayProperties props) {
        this.restClient = asiapayRestClient;
        this.props = props;
    }

    public UpstreamResponse fetchProviders() {
        try {
            var response = restClient.get()
                    .uri(PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + props.token())
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, (req, res) -> {})
                    .toEntity(String.class);
            int status = response.getStatusCode().value();
            String body = response.getBody() == null ? "" : response.getBody();
            if (status >= 500) {
                throw new UpstreamException(UpstreamException.Kind.IO, "upstream 5xx: " + status, null);
            }
            return new UpstreamResponse(status, body);
        } catch (ResourceAccessException e) {
            UpstreamException.Kind kind = e.getCause() instanceof java.net.SocketTimeoutException
                    ? UpstreamException.Kind.TIMEOUT : UpstreamException.Kind.IO;
            throw new UpstreamException(kind, "upstream " + kind.name().toLowerCase(), e);
        }
    }
}
