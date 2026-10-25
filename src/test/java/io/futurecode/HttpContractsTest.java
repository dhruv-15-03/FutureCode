package io.futurecode;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.Socket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class HttpContractsTest {
    private static ConfigurableApplicationContext context;
    private static String origin;
    private static HttpClient client;
    private record Actor(String cookie, String csrf) {}
    @BeforeAll static void start() {
        context = FixtureApplication.start(0);
        origin = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @AfterAll static void stop() { if (client != null) client.close(); if (context != null) context.close(); }
    private static HttpResponse<byte[]> send(String path, String method, JsonNode body, Actor actor, Map<String, String> extra) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(origin + path)).timeout(java.time.Duration.ofSeconds(15));
        if (actor != null) request.header("Cookie", actor.cookie);
        if (body != null) {
            request.header("Origin", origin).header("Content-Type", "application/json");
            if (actor != null) request.header("X-CSRF-Token", actor.csrf);
            request.method(method, HttpRequest.BodyPublishers.ofByteArray(Json.bytes(body)));
        } else request.method(method, HttpRequest.BodyPublishers.noBody());
        extra.forEach(request::header);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    private static Actor actor(String role) throws Exception {
        var response = send("/api/session", "GET", null, null, role == null ? Map.of() : Map.of("X-Test-Role", role));
        JsonNode data = Json.parse(response.body());
        return new Actor(response.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0], Json.text(data, "csrfToken"));
    }
    private static ObjectNode json(HttpResponse<byte[]> response) {
        JsonNode value = Json.parse(response.body()); assertTrue(value instanceof ObjectNode); return (ObjectNode) value;
    }
    @Test void csrfOwnershipStrictRequestKeysAndPrivateRecordsAreEnforcedOverRealHttp() throws Exception {
        Actor alice = actor(null), bob = actor(null);
        ObjectNode attempt = Json.obj("problemKey", "drill:alias", "payload", Json.obj("answer", 0));
        assertEquals(403, send("/api/attempts", "POST", attempt, new Actor(alice.cookie, "forged"), Map.of()).statusCode());
        var saved = send("/api/attempts", "POST", attempt, alice, Map.of());
        assertEquals(201, saved.statusCode()); JsonNode row = json(saved).path("attempt");
        assertEquals(1, row.path("score").intValue()); String path = "/api/attempts/" + Json.text(row, "id");
        assertEquals(404, send(path, "GET", null, bob, Map.of()).statusCode());
        assertEquals(404, send(path, "DELETE", Json.obj(), bob, Map.of()).statusCode());
        assertEquals("no-store", saved.headers().firstValue("Cache-Control").orElseThrow());
        ObjectNode forged = attempt.deepCopy(); ((ObjectNode) forged.path("payload")).put("score", 1);
        assertEquals(400, send("/api/attempts", "POST", forged, alice, Map.of()).statusCode());
        assertEquals(200, send(path, "DELETE", Json.obj(), alice, Map.of()).statusCode());
        assertEquals(0, json(send("/api/attempts", "GET", null, alice, Map.of())).path("items").size());
    }
    @Test void draftReviewPublicationAndSolutionGatePreserveProvenanceAndPrivacy() throws Exception {
        Actor author = actor(null), other = actor(null), admin = actor("admin");
        ObjectNode draft = json(send("/api/contributions", "POST", Json.obj("body", TestData.problem()), author, Map.of()));
        String id = Json.text(draft, "id"), path = "/api/contributions/" + id;
        assertEquals(404, send(path, "GET", null, other, Map.of()).statusCode());
        assertEquals(404, send("/api/problems/" + id, "GET", null, null, Map.of("X-Test-Role", "guest")).statusCode());
        ObjectNode pending = json(send(path + "/submit", "POST", Json.obj("revision", draft.path("revision")), author, Map.of()));
        ObjectNode decision = Json.obj("revision", pending.path("revision"), "decision", "publish", "note", "PRIVATE-MODERATOR-NOTE", "confirmed", true);
        assertEquals(403, send("/api/review/" + id, "POST", decision, author, Map.of()).statusCode());
        assertEquals(409, send(path, "PUT", Json.obj("body", TestData.problem(), "revision", pending.path("revision")), author, Map.of()).statusCode());
        assertEquals(200, send("/api/review/" + id, "POST", decision, admin, Map.of()).statusCode());
        JsonNode publicProblem = json(send("/api/problems/" + id, "GET", null, null, Map.of("X-Test-Role", "guest")));
        assertFalse(publicProblem.path("body").has("reference")); assertFalse(publicProblem.path("body").has("correctIndex")); assertFalse(publicProblem.has("reviewNote"));
        assertEquals(403, send("/api/problems/" + id + "/solution", "GET", null, other, Map.of()).statusCode());
        int revision = publicProblem.path("revision").intValue();
        var response = send("/api/attempts", "POST", Json.obj("problemKey", "community:" + id, "payload", Json.obj("revision", revision, "answer", 0, "explanation", "")), other, Map.of());
        assertEquals(201, response.statusCode()); assertEquals("community", Json.text(json(response).path("attempt"), "category"));
        assertEquals(200, send("/api/problems/" + id + "/solution", "GET", null, other, Map.of()).statusCode());
        String catalog = new String(send("/api/catalog", "GET", null, null, Map.of("X-Test-Role", "guest")).body(), StandardCharsets.UTF_8);
        assertFalse(catalog.contains("PRIVATE-MODERATOR-NOTE")); assertFalse(catalog.contains("fixture@example.test"));
    }
    @Test void withdrawalRejectionAndStaleRevisionsNeverSilentlyOverwriteAReview() throws Exception {
        Actor author = actor(null), admin = actor("admin");
        JsonNode row = json(send("/api/contributions", "POST", Json.obj("body", TestData.problem()), author, Map.of()));
        String path = "/api/contributions/" + Json.text(row, "id");
        row = json(send(path + "/submit", "POST", Json.obj("revision", row.path("revision")), author, Map.of()));
        assertEquals(409, send(path + "/withdraw", "POST", Json.obj("revision", row.path("revision").intValue() - 1), author, Map.of()).statusCode());
        row = json(send(path + "/withdraw", "POST", Json.obj("revision", row.path("revision")), author, Map.of()));
        assertEquals("draft", Json.text(row, "status"));
        row = json(send(path + "/submit", "POST", Json.obj("revision", row.path("revision")), author, Map.of()));
        row = json(send("/api/review/" + Json.text(row, "id"), "POST", Json.obj("revision", row.path("revision"), "decision", "reject", "note", "Clarify assumptions.", "confirmed", true), admin, Map.of()));
        assertEquals("rejected", Json.text(row, "status")); assertEquals("Clarify assumptions.", Json.text(row, "reviewNote"));
        assertEquals("draft", Json.text(json(send(path, "PUT", Json.obj("revision", row.path("revision"), "body", TestData.problem()), author, Map.of())), "status"));
    }
    @Test void catalogPaginationAndUnsupportedFiltersUseTheExistingCursorContract() throws Exception {
        MemoryStore store = context.getBean(MemoryStore.class);
        Store.User user = store.upsertGoogleUser(new Store.Identity(UUID.randomUUID().toString(), "pagination@example.test", "Pagination"));
        for (int index = 0; index < 23; index++) {
            if (index > 0 && index % 8 == 0) user = store.upsertGoogleUser(new Store.Identity(UUID.randomUUID().toString(), "pagination@example.test", "Pagination"));
            ObjectNode body = TestData.problem(); body.put("title", "Pagination fixture " + index);
            ObjectNode row = store.saveProblem(user.id(), body, null, null);
            synchronized (store) { store.problems.get(Json.text(row, "id")).put("status", "published"); }
        }
        JsonNode first = json(send("/api/catalog?search=Pagination", "GET", null, null, Map.of("X-Test-Role", "guest")));
        assertEquals(20, first.path("community").size()); String cursor = Json.text(first, "nextCursor"); assertFalse(cursor.isEmpty());
        JsonNode second = json(send("/api/catalog?search=Pagination&cursor=" + cursor, "GET", null, null, Map.of("X-Test-Role", "guest")));
        assertEquals(3, second.path("community").size()); assertTrue(second.path("builtins").isEmpty());
        assertEquals(400, send("/api/catalog?status=draft", "GET", null, null, Map.of()).statusCode());
        assertEquals(400, send("/api/catalog?cursor=invalid", "GET", null, null, Map.of()).statusCode());
    }
    @Test void sourceAssetsAreAllowlistedAndErrorsNeverExposeServerFiles() throws Exception {
        for (String path : List.of("/", "/privacy", "/styles.css", "/app.js")) {
            var response = send(path, "GET", null, null, Map.of()); assertEquals(200, response.statusCode());
            assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow());
            assertTrue(response.headers().firstValue("Content-Security-Policy").orElseThrow().contains("connect-src 'self'"));
        }
        for (String path : List.of("/.env", "/pom.xml", "/src/main/resources/application.yaml", "/tests/browser/server.mjs", "/actuator", "/?source=secret", "/api/execute")) assertEquals(404, send(path, "GET", null, null, Map.of()).statusCode(), path);
    }
    @Test void wrongHostAndOversizedOrMalformedBodiesAreRejectedBeforeDomainWork() throws Exception {
        int port = URI.create(origin).getPort();
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5000); socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: evil.test\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8); assertTrue(response.startsWith("HTTP/1.1 403"));
        }
        HttpRequest malformed = HttpRequest.newBuilder(URI.create(origin + "/api/attempts")).header("Origin", origin).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{")).build();
        assertEquals(400, client.send(malformed, HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(413, send("/api/attempts", "POST", Json.obj("large", "x".repeat(33000)), null, Map.of()).statusCode());
        HttpRequest wrongOrigin = HttpRequest.newBuilder(URI.create(origin + "/api/attempts")).header("Origin", "https://evil.test").header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
        assertEquals(403, client.send(wrongOrigin, HttpResponse.BodyHandlers.discarding()).statusCode());
    }
}
