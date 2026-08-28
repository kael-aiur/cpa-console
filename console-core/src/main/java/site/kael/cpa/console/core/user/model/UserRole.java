package site.kael.cpa.console.core.user.model;

public enum UserRole {
    USER,
    ADMIN;

    public String value() {
        return name().toLowerCase();
    }

    public static UserRole fromValue(String value) {
        return "admin".equalsIgnoreCase(value) ? ADMIN : USER;
    }
}
