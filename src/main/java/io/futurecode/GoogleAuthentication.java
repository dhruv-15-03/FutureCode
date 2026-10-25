package io.futurecode;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import tools.jackson.databind.JsonNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class GoogleAuthentication implements Authentication {
    private static final Set<String> DESTINATIONS = Set.of("catalog", "account", "contribute", "attempts", "interview", "library", "private");
    private final AppSettings settings;
    private final Store store;
    private final BoundedHttp http;
    private final Clock clock;
    private volatile Map<String, JwtDecoder> decoders = Map.of();
    private volatile long keysExpire;

    public GoogleAuthentication(AppSettings settings, Store store, BoundedHttp http) { this(settings, store, http, Clock.systemUTC()); }
    GoogleAuthentication(AppSettings settings, Store store, BoundedHttp http, Clock clock) {
        this.settings = settings; this.store = store; this.http = http; this.clock = clock;
    }
    @Override public boolean available() { return settings.authConfigured() && store != null; }
    private void ready() {
        if (!available()) throw new ApiError(503, "Google sign-in is not configured yet. Browsing is available; connect MySQL and Google OAuth to enable submissions.", "sign_in_unavailable");
    }
    private String cookie(String name, String value, int seconds) {
        return name + "=" + value + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + seconds + (settings.production() ? "; Secure" : "");
    }
    private String sign(String value) { return Crypto.hmac(settings.sessionSecret(), value); }
    private String csrf(String value) { return sign("csrf:" + value); }
    @Override public String clearFlow() { return cookie(settings.flowCookie(), "", 0); }
    @Override public String clearSession() { return cookie(settings.sessionCookie(), "", 0); }

    public static String readCookie(String header, String name) {
        if (header == null || header.length() > 8192) return null;
        String found = null;
        for (String item : header.split(";")) {
            String part = item.strip();
            if (part.startsWith(name + "=")) {
                if (found != null) return null;
                found = part.substring(name.length() + 1);
            }
        }
        return found;
    }
    static String cookieHeader(HttpServletRequest request) { return String.join(";", Collections.list(request.getHeaders("Cookie"))); }
    private static String form(Map<String, String> values) {
        return values.entrySet().stream().map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
    }
    @Override public Start begin(String returnTo) {
        ready();
        if (!DESTINATIONS.contains(returnTo)) throw new ApiError(400, "Choose a FutureCode destination after sign-in.", "invalid_destination");
        String verifier = Crypto.random();
        JsonNode flow = Json.obj("state", Crypto.random(), "nonce", Crypto.random(), "verifier", verifier, "expires", clock.millis() + 600000, "returnTo", returnTo);
        String encoded = Crypto.encode(Json.bytes(flow));
        String url = "https://accounts.google.com/o/oauth2/v2/auth?" + form(Map.ofEntries(
            Map.entry("client_id", settings.googleClientId()), Map.entry("redirect_uri", settings.callbackUri()), Map.entry("response_type", "code"),
            Map.entry("scope", "openid email profile"), Map.entry("access_type", "online"), Map.entry("prompt", "select_account"),
            Map.entry("state", Json.text(flow, "state")), Map.entry("nonce", Json.text(flow, "nonce")), Map.entry("code_challenge_method", "S256"),
            Map.entry("code_challenge", Crypto.encode(Crypto.shaBytes(verifier.getBytes(StandardCharsets.UTF_8))))));
        return new Start(url, cookie(settings.flowCookie(), encoded + "." + sign(encoded), 600));
    }
    @Override public Finish finish(Map<String, List<String>> query, String cookieHeader) {
        ready();
        JsonNode flow;
        try {
            String raw = readCookie(cookieHeader, settings.flowCookie());
            if (raw == null || raw.length() > 2048) throw new IllegalArgumentException();
            String[] parts = raw.split("\\.", -1);
            if (parts.length != 2 || !Crypto.equal(sign(parts[0]), parts[1])) throw new IllegalArgumentException();
            flow = Json.parse(Crypto.decode(parts[0]));
            if (!Json.exact(flow, "state", "nonce", "verifier", "expires", "returnTo") ||
                !Json.text(flow, "state").matches("[A-Za-z0-9_-]{43}") || !Json.text(flow, "nonce").matches("[A-Za-z0-9_-]{43}") ||
                !Json.text(flow, "verifier").matches("[A-Za-z0-9._~-]{43,128}") || !flow.path("expires").isIntegralNumber() ||
                flow.path("expires").longValue() <= clock.millis() || flow.path("expires").longValue() > clock.millis() + 600000 ||
                !DESTINATIONS.contains(Json.text(flow, "returnTo")) || query.getOrDefault("state", List.of()).size() != 1 ||
                !Crypto.equal(query.get("state").getFirst(), Json.text(flow, "state"))) throw new IllegalArgumentException();
        } catch (IllegalArgumentException | ApiError error) {
            throw new ApiError(401, "Sign-in expired or could not be matched to this browser. Start again.", "sign_in_expired");
        }
        if (query.containsKey("error")) throw new ApiError(401, "Google sign-in was cancelled or denied. You can continue browsing.", "sign_in_cancelled");
        if (query.getOrDefault("code", List.of()).size() != 1 || !Json.bounded(query.get("code").getFirst(), 4096, true)) throw signInFailed();
        Store.Identity identity;
        try {
            byte[] body = form(Map.of("code", query.get("code").getFirst(), "client_id", settings.googleClientId(), "client_secret", settings.googleClientSecret(),
                "redirect_uri", settings.callbackUri(), "grant_type", "authorization_code", "code_verifier", Json.text(flow, "verifier"))).getBytes(StandardCharsets.UTF_8);
            BoundedHttp.Response response = http.request(BoundedHttp.TOKEN, Map.of("Content-Type", "application/x-www-form-urlencoded"), body, 32768, Duration.ofSeconds(10));
            if (response.status() != 200) throw signInFailed();
            String token = Json.text(Json.parse(response.body()), "id_token");
            if (token.length() > 16000 || token.isEmpty()) throw signInFailed();
            SignedJWT parsed = SignedJWT.parse(token);
            if (!parsed.getHeader().getAlgorithm().getName().equals("RS256")) throw signInFailed();
            JwtDecoder decoder = decoder(parsed.getHeader().getKeyID());
            Jwt verified = decoder.decode(token);
            String subject = verified.getSubject();
            Object email = verified.getClaim("email"), name = verified.getClaim("name"), nonce = verified.getClaim("nonce");
            Object authorizedParty = verified.getClaim("azp");
            if (!Json.bounded(subject, 255, true) || !subject.matches("[!-~]+") || !(email instanceof String address) ||
                !Json.bounded(address, 254, true) || !address.matches("[^\\s@]+@[^\\s@]+") ||
                !Boolean.TRUE.equals(verified.getClaim("email_verified")) || !(nonce instanceof String nonceText) || !Crypto.equal(nonceText, Json.text(flow, "nonce")) ||
                (name != null && (!(name instanceof String text) || !Json.bounded(text, 255, false))) ||
                ((authorizedParty != null || verified.getAudience().size() > 1) && !settings.googleClientId().equals(authorizedParty)) ||
                verified.getIssuedAt() == null || verified.getIssuedAt().isAfter(clock.instant().plusSeconds(300))) throw signInFailed();
            identity = new Store.Identity(subject, address.toLowerCase(Locale.ROOT), name instanceof String text ? text : "");
        } catch (Exception error) {
            // Token, HTTP, and verifier exceptions can contain credentials.
            throw signInFailed();
        }
        Store.User user = store.upsertGoogleUser(identity);
        String raw = Crypto.random();
        store.createSession(user.id(), Crypto.sha(raw), Crypto.sha(csrf(raw)), clock.instant().plus(Duration.ofDays(7)));
        return new Finish(Json.text(flow, "returnTo"), cookie(settings.sessionCookie(), raw, 7 * 86400));
    }
    private synchronized JwtDecoder decoder(String kid) throws java.text.ParseException, com.nimbusds.jose.JOSEException {
        if (kid == null) throw signInFailed();
        if (clock.millis() >= keysExpire || !decoders.containsKey(kid)) {
            BoundedHttp.Response response = http.request(BoundedHttp.JWKS, Map.of(), null, 65536, Duration.ofSeconds(10));
            if (response.status() != 200) throw signInFailed();
            JWKSet keys = JWKSet.parse(new String(response.body(), StandardCharsets.UTF_8));
            if (keys.getKeys().size() > 16) throw signInFailed();
            Map<String, JwtDecoder> replacement = new HashMap<>();
            for (var key : keys.getKeys()) {
                if (key instanceof RSAKey rsa && key.getKeyID() != null && !rsa.isPrivate()) {
                    NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(rsa.toRSAPublicKey()).build();
                    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefault(),
                        new JwtClaimValidator<List<String>>("aud", value -> value != null && value.contains(settings.googleClientId())),
                        new JwtClaimValidator<String>("iss", Set.of("accounts.google.com", "https://accounts.google.com")::contains),
                        new JwtClaimValidator<Instant>("exp", Objects::nonNull)));
                    replacement.put(key.getKeyID(), decoder);
                }
            }
            decoders = Map.copyOf(replacement); keysExpire = clock.millis() + 3600000;
        }
        JwtDecoder decoder = decoders.get(kid);
        if (decoder == null) throw signInFailed();
        return decoder;
    }
    @Override public Session resolve(HttpServletRequest request) {
        if (!available()) return null;
        String raw = readCookie(cookieHeader(request), settings.sessionCookie());
        if (raw == null || !raw.matches("[A-Za-z0-9_-]{43}")) return null;
        Store.StoredSession session = store.getSession(Crypto.sha(raw));
        if (session == null || !Crypto.equal(session.csrfHash(), Crypto.sha(csrf(raw)))) return null;
        return new Session(session.user(), csrf(raw), Crypto.sha(raw), session.authenticatedAt());
    }
    @Override public void logout(Session session) { store.removeSession(session.tokenHash()); }
    private static ApiError signInFailed() { return new ApiError(401, "Google identity could not be verified. Start sign-in again.", "sign_in_failed"); }
}
