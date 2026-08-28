package site.kael.cpa.console.core.cpa.exception;

public class CpaUnavailableException extends RuntimeException {
    public CpaUnavailableException(Throwable cause) {
        super("CPA service is unavailable", cause);
    }
}
