package io.futurecode;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.InvalidMediaTypeException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.net.URI;
import java.util.Set;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class RequestBoundary extends OncePerRequestFilter {
    public static final String BODY = "futurecode.jsonBody";
    public static final String RAW_BODY = "futurecode.rawBody";
    private final AppSettings settings;
    public RequestBoundary(AppSettings settings) { this.settings = settings; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self'; connect-src 'self'; img-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");
        if (settings.production()) response.setHeader("Strict-Transport-Security", "max-age=31536000");
        try {
            Set<String> hosts = settings.production() ? Set.of(URI.create(settings.origin()).getRawAuthority()) :
                Set.of("127.0.0.1:" + request.getLocalPort(), "localhost:" + request.getLocalPort());
            if (!hosts.contains(request.getHeader("Host"))) throw new ApiError(403, "Use the configured FutureCode host.", "invalid_origin");
            int urlLength = request.getRequestURI().length() + (request.getQueryString() == null ? 0 : request.getQueryString().length());
            if (urlLength > 8192) throw ApiError.bad("The request URL is too long.");
            if (request.getRequestURI().startsWith("/api/") && Set.of("POST", "PUT", "PATCH", "DELETE").contains(request.getMethod())) {
                String origin = request.getHeader("Origin");
                boolean valid = settings.production() ? settings.origin().equals(origin) : hosts.stream().anyMatch(host -> ("http://" + host).equals(origin));
                boolean paymentWebhook = request.getRequestURI().equals("/api/payments/webhook") && request.getMethod().equals("POST");
                if ((!valid && !paymentWebhook) || !jsonContentType(request.getContentType())) throw new ApiError(403, "Submit JSON from this FutureCode origin.", "invalid_origin");
                int limit = request.getRequestURI().startsWith("/api/contributions") ? 65536 : 32768;
                if (request.getContentLengthLong() > limit) throw new ApiError(413, "Request exceeds " + limit / 1024 + " KB.", "request_too_large");
                byte[] body = request.getInputStream().readNBytes(limit + 1);
                if (body.length > limit) throw new ApiError(413, "Request exceeds " + limit / 1024 + " KB.", "request_too_large");
                if (paymentWebhook) request.setAttribute(RAW_BODY, body);
                request.setAttribute(BODY, Json.parse(body));
            }
            chain.doFilter(request, response);
        } catch (ApiError error) {
            if (!response.isCommitted()) writeError(response, error);
        }
    }
    private static boolean jsonContentType(String value) {
        try {
            MediaType media = MediaType.parseMediaType(value);
            return media.getType().equalsIgnoreCase("application") && media.getSubtype().equalsIgnoreCase("json") &&
                (media.getCharset() == null || java.nio.charset.StandardCharsets.UTF_8.equals(media.getCharset()));
        } catch (InvalidMediaTypeException | java.nio.charset.IllegalCharsetNameException | java.nio.charset.UnsupportedCharsetException error) {
            throw new ApiError(403, "Submit UTF-8 JSON from this FutureCode origin.", "invalid_origin");
        }
    }
    public static void writeError(HttpServletResponse response, ApiError error) throws IOException {
        response.setStatus(error.status()); response.setContentType("application/json; charset=utf-8");
        response.getOutputStream().write(Json.bytes(Json.obj("error", error.getMessage(), "code", error.code())));
    }
}
