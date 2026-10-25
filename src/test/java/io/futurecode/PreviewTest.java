package io.futurecode;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import static org.junit.jupiter.api.Assertions.*;

class PreviewTest {
    @Test void realProductionClassesDoNotAcceptSyntheticIdentitiesOrFakeSuccessfulStorage() throws Exception {
        try (var context = new SpringApplicationBuilder(FutureCodeApplication.class).run(
            "--server.port=0", "--server.address=127.0.0.1", "--futurecode.environment=development",
            "--futurecode.origin=http://127.0.0.1:4317", "--futurecode.command=serve", "--futurecode.database-url=",
            "--futurecode.google-client-id=", "--futurecode.google-client-secret=", "--futurecode.session-secret=", "--futurecode.mysql-ca-file=");
             HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            String origin = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
            var request = HttpRequest.newBuilder(URI.create(origin + "/api/session")).header("X-Test-Role", "admin").header("Cookie", "futurecode_test=forged").build();
            var session = Json.parse(client.send(request, HttpResponse.BodyHandlers.ofByteArray()).body());
            assertTrue(session.path("user").isNull()); assertFalse(session.path("features").path("database").booleanValue());
            assertFalse(session.path("features").path("googleSignIn").booleanValue()); assertTrue(Json.text(session, "notice").contains("Preview"));
            var save = HttpRequest.newBuilder(URI.create(origin + "/api/attempts")).header("Origin", origin).header("Content-Type", "application/json")
                .header("X-Test-Role", "admin").POST(HttpRequest.BodyPublishers.ofString("{\"problemKey\":\"drill:alias\",\"payload\":{\"answer\":0}}")).build();
            assertEquals(503, client.send(save, HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(503, client.send(HttpRequest.newBuilder(URI.create(origin + "/readyz")).build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            assertEquals(200, client.send(HttpRequest.newBuilder(URI.create(origin + "/livez")).build(), HttpResponse.BodyHandlers.discarding()).statusCode());
            var callback = client.send(HttpRequest.newBuilder(URI.create(origin + "/auth/google/callback?code=PRIVATE-CODE&state=unmatched")).build(), HttpResponse.BodyHandlers.discarding());
            assertEquals(303, callback.statusCode()); String location = callback.headers().firstValue("Location").orElseThrow();
            assertTrue(location.endsWith("notice=sign_in_unavailable")); assertFalse(location.contains("PRIVATE"));
            assertTrue(callback.headers().firstValue("Set-Cookie").orElseThrow().contains("Max-Age=0"));
        }
    }
}
