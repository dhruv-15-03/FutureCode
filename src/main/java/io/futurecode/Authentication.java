package io.futurecode;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface Authentication {
    record Session(Store.User user, String csrfToken, String tokenHash, Instant authenticatedAt) {}
    record Start(String url, String cookie) {}
    record Finish(String destination, String cookie) {}
    boolean available();
    Session resolve(HttpServletRequest request);
    Start begin(String returnTo);
    Finish finish(Map<String, List<String>> query, String cookieHeader);
    String clearFlow();
    String clearSession();
    void logout(Session session);
    default void requireCsrf(Session session, String token) {
        if (session == null || !Crypto.equal(session.csrfToken(), token)) throw new ApiError(403, "Your session changed. Reload before submitting again.", "csrf_failed");
    }
}
