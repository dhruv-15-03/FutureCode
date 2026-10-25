package io.futurecode;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class GoogleAuthenticationTest {
    private static RSAKey rsa;
    @BeforeAll static void keys() throws Exception { rsa = new RSAKeyGenerator(2048).keyID("fixture").generate(); }
    private static Map<String, String> query(String value) {
        Map<String, String> result = new HashMap<>();
        for (String part : value.split("&")) {
            String[] pair = part.split("=", 2);
            result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8), URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return result;
    }
    private static final class GoogleHttp extends BoundedHttp {
        String nonce;
        String variant = "";
        int calls;
        String verifier;
        @Override public Response request(URI uri, Map<String, String> headers, byte[] body, int max, Duration timeout) {
            calls++;
            if (uri.equals(JWKS)) return new Response(200, Json.bytes(Map.of("keys", List.of(rsa.toPublicJWK().toJSONObject()))));
            assertEquals(TOKEN, uri); assertEquals(32768, max); assertEquals(Duration.ofSeconds(10), timeout);
            Map<String, String> fields = query(new String(body, StandardCharsets.UTF_8));
            verifier = fields.get("code_verifier");
            assertEquals("fixture-not-a-live-secret", fields.get("client_secret"));
            assertEquals("http://127.0.0.1:4318/auth/google/callback", fields.get("redirect_uri"));
            try {
                Instant now = Instant.now();
                JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(variant.equals("issuer") ? "https://evil.test" : "https://accounts.google.com")
                    .audience(variant.equals("audience") ? "wrong-client" : "fixture.apps.googleusercontent.com").subject("fixture-google-sub")
                    .issueTime(Date.from(now.minusSeconds(variant.equals("expired") ? 7200 : 5))).expirationTime(Date.from(now.plusSeconds(variant.equals("expired") ? -600 : 3600)))
                    .claim("nonce", variant.equals("nonce") ? "wrong-nonce" : nonce).claim("email", "Reader@Example.test")
                    .claim("email_verified", !variant.equals("email")).claim("name", "Reader").claim("role", "admin").build();
                SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("fixture").build(), claims);
                jwt.sign(new RSASSASigner(rsa)); String token = jwt.serialize();
                if (variant.equals("signature")) token = token.substring(0, token.length() - 8) + "AAAAAAAA";
                return new Response(200, Json.bytes(Json.obj("id_token", token, "access_token", "PRIVATE-UNSTORED-ACCESS-TOKEN")));
            } catch (com.nimbusds.jose.JOSEException error) { throw new AssertionError(error); }
        }
    }
    private static Map<String, List<String>> callback(String state) { return Map.of("state", List.of(state), "code", List.of("synthetic-code")); }
    @Test void pkceStateNonceVerifiedIdentityHashedSessionsCsrfAndLogoutWorkTogether() {
        AppSettings settings = TestData.settings(true); MemoryStore store = new MemoryStore(settings);
        try (GoogleHttp http = new GoogleHttp()) {
            GoogleAuthentication auth = new GoogleAuthentication(settings, store, http);
            Authentication.Start begin = auth.begin("account"); Map<String, String> url = query(URI.create(begin.url()).getRawQuery());
            assertEquals("https://accounts.google.com", URI.create(begin.url()).getScheme() + "://" + URI.create(begin.url()).getHost());
            assertEquals("S256", url.get("code_challenge_method")); assertEquals("online", url.get("access_type")); assertEquals("openid email profile", url.get("scope"));
            assertTrue(begin.cookie().contains("HttpOnly")); http.nonce = url.get("nonce");
            assertEquals("sign_in_expired", assertThrows(ApiError.class, () -> auth.finish(callback("forged"), begin.cookie().split(";")[0])).code()); assertEquals(0, http.calls);
            Authentication.Finish finish = auth.finish(callback(url.get("state")), begin.cookie().split(";")[0]);
            assertEquals(url.get("code_challenge"), Crypto.encode(Crypto.shaBytes(http.verifier.getBytes(StandardCharsets.UTF_8))));
            String cookie = finish.cookie().split(";")[0], raw = GoogleAuthentication.readCookie(cookie, settings.sessionCookie());
            assertTrue(store.sessions.containsKey(Crypto.sha(raw))); assertFalse(store.sessions.containsKey(raw));
            MockHttpServletRequest request = new MockHttpServletRequest(); request.addHeader("Cookie", cookie);
            Authentication.Session session = auth.resolve(request);
            assertNotNull(session); assertEquals("student", session.user().role()); assertEquals("reader@example.test", session.user().email());
            auth.requireCsrf(session, session.csrfToken()); assertEquals(403, assertThrows(ApiError.class, () -> auth.requireCsrf(session, "forged")).status());
            auth.logout(session); assertNull(auth.resolve(request));
            assertThrows(ApiError.class, () -> auth.begin("//evil.test"));
            assertNull(GoogleAuthentication.readCookie("futurecode_session=a; futurecode_session=b", "futurecode_session"));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"email", "nonce", "audience", "issuer", "expired", "signature"})
    void signatureClaimsAndEmailFailuresDoNotCreateAccountsOrEchoProviderData(String variant) {
        AppSettings settings = TestData.settings(true); MemoryStore store = new MemoryStore(settings);
        try (GoogleHttp http = new GoogleHttp()) {
            http.variant = variant; GoogleAuthentication auth = new GoogleAuthentication(settings, store, http);
            var begin = auth.begin("account"); Map<String, String> values = query(URI.create(begin.url()).getRawQuery()); http.nonce = values.get("nonce");
            ApiError error = assertThrows(ApiError.class, () -> auth.finish(callback(values.get("state")), begin.cookie().split(";")[0]));
            assertEquals(401, error.status()); assertFalse(error.getMessage().contains("PRIVATE")); assertTrue(store.users.isEmpty()); assertTrue(store.sessions.isEmpty());
        }
    }
    @Test void expiredAndTamperedFlowCookiesNeverReachGoogle() {
        AppSettings settings = TestData.settings(true); MemoryStore store = new MemoryStore(settings);
        try (GoogleHttp http = new GoogleHttp()) {
            Instant now = Instant.now(); GoogleAuthentication initial = new GoogleAuthentication(settings, store, http, Clock.fixed(now, ZoneOffset.UTC));
            var begin = initial.begin("catalog"); String state = query(URI.create(begin.url()).getRawQuery()).get("state");
            GoogleAuthentication later = new GoogleAuthentication(settings, store, http, Clock.fixed(now.plusSeconds(601), ZoneOffset.UTC));
            assertEquals("sign_in_expired", assertThrows(ApiError.class, () -> later.finish(callback(state), begin.cookie())).code());
            assertEquals("sign_in_expired", assertThrows(ApiError.class, () -> initial.finish(callback(state), begin.cookie().split(";")[0] + "tampered")).code());
            assertEquals(0, http.calls);
        }
    }
}
