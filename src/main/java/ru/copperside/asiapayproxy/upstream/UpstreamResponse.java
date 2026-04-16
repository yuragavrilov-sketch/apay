package ru.copperside.asiapayproxy.upstream;

public record UpstreamResponse(int status, String body) {
    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }
    public boolean isClientError() {
        return status >= 400 && status < 500;
    }
}
