package site.kael.cpa.console.common.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import site.kael.cpa.console.core.cpa.exception.CpaManagementException;
import site.kael.cpa.console.core.cpa.exception.CpaUnavailableException;
import site.kael.cpa.console.core.cpa.exception.InvalidCpaApiKeyException;
import site.kael.cpa.console.core.user.exception.UserNotFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(InvalidCpaApiKeyException.class)
    public ResponseEntity<ApiErrorResponse> invalidApiKey(InvalidCpaApiKeyException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiErrorResponse("INVALID_API_KEY", "API Key 无效"));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiErrorResponse> badCredentials(BadCredentialsException exception) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiErrorResponse("INVALID_CREDENTIALS", exception.getMessage()));
    }

    @ExceptionHandler(CpaManagementException.class)
    public ResponseEntity<ApiErrorResponse> cpaManagement(CpaManagementException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ApiErrorResponse("CPA_MANAGEMENT_FAILED", exception.getMessage()));
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> userNotFound(UserNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ApiErrorResponse("USER_NOT_FOUND", "用户不存在"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ApiErrorResponse("BAD_REQUEST", exception.getMessage()));
    }

    @ExceptionHandler(CpaUnavailableException.class)
    public ResponseEntity<ApiErrorResponse> cpaUnavailable(CpaUnavailableException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ApiErrorResponse("CPA_UNAVAILABLE", "CPA 服务暂不可用"));
    }
}
