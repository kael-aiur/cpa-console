package site.kael.cpa.console.core.user.exception;

public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(long id) {
        super("User not found: " + id);
    }
}
