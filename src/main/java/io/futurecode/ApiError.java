package io.futurecode;

public final class ApiError extends RuntimeException {
    private final int status;
    private final String code;

    public ApiError(int status, String message, String code) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() { return status; }
    public String code() { return code; }
    public static ApiError bad(String message) { return new ApiError(400, message, "invalid_input"); }
    public static ApiError missing() { return new ApiError(404, "Not found.", "not_found"); }
    public static ApiError storage() { return new ApiError(503, "The database is unavailable or busy. Your changes were not confirmed.", "database_unavailable"); }
}
