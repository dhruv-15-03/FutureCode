package io.futurecode;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@TestConfiguration(proxyBeanMethods = false)
public class FixtureConfiguration {
    @Bean @Primary MemoryStore fixtureStore(AppSettings settings) { return new MemoryStore(settings); }
    @Bean @Primary FixtureAuthentication fixtureAuthentication(MemoryStore store) { return new FixtureAuthentication(store); }
    @Bean FilterRegistrationBean<OncePerRequestFilter> syntheticSessionFilter(FixtureAuthentication auth) {
        var bean = new FilterRegistrationBean<OncePerRequestFilter>();
        bean.setOrder(Integer.MIN_VALUE + 1);
        bean.setFilter(new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
                if (request.getMethod().equals("GET") && request.getRequestURI().equals("/api/session")) auth.bootstrap(request, response);
                chain.doFilter(request, response);
            }
        });
        return bean;
    }
    static final class FixtureAuthentication implements Authentication {
        private final MemoryStore store;
        private final Map<String, Session> sessions = new HashMap<>();
        FixtureAuthentication(MemoryStore store) { this.store = store; }
        @Override public boolean available() { return true; }
        synchronized void bootstrap(HttpServletRequest request, HttpServletResponse response) {
            if ("guest".equals(request.getHeader("X-Test-Role")) || resolve(request) != null) return;
            String id = UUID.randomUUID().toString(), token = UUID.randomUUID().toString();
            Store.User user = new Store.User(id, "fixture@example.test", "Browser fixture", "Test reader", "admin".equals(request.getHeader("X-Test-Role")) ? "admin" : "student");
            synchronized (store) { store.users.put(id, user); }
            Session session = new Session(user, UUID.randomUUID().toString(), token, Instant.now());
            sessions.put(token, session); request.setAttribute("fixture.session", session);
            response.addHeader("Set-Cookie", "futurecode_test=" + token + "; HttpOnly; SameSite=Lax; Path=/");
        }
        @Override public synchronized Session resolve(HttpServletRequest request) {
            if ("guest".equals(request.getHeader("X-Test-Role"))) return null;
            Object bootstrapped = request.getAttribute("fixture.session");
            if (bootstrapped instanceof Session session) return session;
            String token = GoogleAuthentication.readCookie(GoogleAuthentication.cookieHeader(request), "futurecode_test");
            Session old = sessions.get(token);
            Store.User user;
            synchronized (store) { user = old == null ? null : store.users.get(old.user().id()); }
            return user == null ? null : new Session(user, old.csrfToken(), old.tokenHash(), old.authenticatedAt());
        }
        @Override public Start begin(String returnTo) { return new Start("https://accounts.google.com/futurecode-test-only", "futurecode_test_flow=fixture; HttpOnly; SameSite=Lax; Path=/"); }
        @Override public Finish finish(Map<String, List<String>> query, String cookie) { throw new ApiError(401, "Synthetic fixture does not verify Google identities.", "sign_in_failed"); }
        @Override public String clearFlow() { return "futurecode_test_flow=; Max-Age=0; Path=/"; }
        @Override public String clearSession() { return "futurecode_test=; Max-Age=0; Path=/"; }
        @Override public synchronized void logout(Session session) { sessions.remove(session.tokenHash()); }
    }
}
