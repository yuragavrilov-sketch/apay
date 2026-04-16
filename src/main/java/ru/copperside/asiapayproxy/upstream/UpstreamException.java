package ru.copperside.asiapayproxy.upstream;

public class UpstreamException extends RuntimeException {
    public enum Kind { TIMEOUT, IO }
    private final Kind kind;
    public UpstreamException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }
    public Kind kind() { return kind; }
}
