package site.kael.cpa.console.core.cpa.exception;

public class CpaManagementException extends RuntimeException {
    public CpaManagementException(String message) {
        super(message);
    }

    public CpaManagementException(String message, Throwable cause) {
        super(message, cause);
    }
}
