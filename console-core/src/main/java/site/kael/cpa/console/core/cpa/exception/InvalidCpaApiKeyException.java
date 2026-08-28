package site.kael.cpa.console.core.cpa.exception;

public class InvalidCpaApiKeyException extends RuntimeException {
    public InvalidCpaApiKeyException() {
        super("CPA API Key is invalid");
    }
}
