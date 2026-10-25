package io.futurecode;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CommunityContractsTest {
    private static ConfigurableApplicationContext context;
    private static HttpClient client;
    private static String origin;
    private record Actor(String cookie, String csrf) {}
    @BeforeAll static void start() {
        context = FixtureApplication.start(0); origin = "http://127.0.0.1:" + context.getEnvironment().getProperty("local.server.port");
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    }
    @AfterAll static void stop() { if (client != null) client.close(); if (context != null) context.close(); }
    private static HttpResponse<byte[]> call(String path, String method, JsonNode body, Actor actor) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(origin + path));
        if (actor != null) request.header("Cookie", actor.cookie);
        if (body != null) {
            request.header("Origin", origin).header("Content-Type", "application/json");
            if (actor != null) request.header("X-CSRF-Token", actor.csrf);
            request.method(method, HttpRequest.BodyPublishers.ofByteArray(Json.bytes(body)));
        } else request.method(method, HttpRequest.BodyPublishers.noBody());
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }
    private static JsonNode data(HttpResponse<byte[]> response) { return Json.parse(response.body()); }
    private static Actor actor(boolean admin) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(origin + "/api/session"));
        if (admin) request.header("X-Test-Role", "admin");
        var response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        return new Actor(response.headers().firstValue("Set-Cookie").orElseThrow().split(";")[0], Json.text(data(response), "csrfToken"));
    }
    private static JsonNode attempt(Actor actor, int answer) throws Exception {
        var response = call("/api/attempts", "POST", Json.obj("problemKey", "drill:alias", "payload", Json.obj("answer", answer)), actor);
        assertEquals(201, response.statusCode()); return data(response).path("attempt");
    }
    @Test void bookmarksArePrivateIdempotentAndAcceptNoSuppliedOwnerOrScore() throws Exception {
        Actor alice = actor(false), bob = actor(false);
        assertEquals(401, call("/api/bookmarks/drill:alias", "PUT", Json.obj(), null).statusCode());
        assertEquals(200, call("/api/bookmarks/drill:alias", "PUT", Json.obj(), alice).statusCode());
        assertEquals(200, call("/api/bookmarks/drill:alias", "PUT", Json.obj(), alice).statusCode());
        assertEquals(1, data(call("/api/bookmarks", "GET", null, alice)).path("items").size());
        assertEquals(0, data(call("/api/bookmarks", "GET", null, bob)).path("items").size());
        assertEquals(400, call("/api/bookmarks/drill:alias", "PUT", Json.obj("userId", "forged"), alice).statusCode());
        assertEquals(403, call("/api/bookmarks/drill:alias", "DELETE", Json.obj(), new Actor(alice.cookie, "forged")).statusCode());
        assertEquals(200, call("/api/bookmarks/drill:alias", "DELETE", Json.obj(), alice).statusCode());
        assertEquals(0, data(call("/api/bookmarks", "GET", null, alice)).path("items").size());
    }
    @Test void discussionRequiresAnAttemptOwnershipRevisionAndAuditedModeratorActions() throws Exception {
        Actor alice = actor(false), bob = actor(false), admin = actor(true);
        String path = "/api/community/drill:alias/discussion";
        assertEquals(403, call(path, "GET", null, alice).statusCode());
        assertEquals(403, call(path, "POST", Json.obj("text", "spoiler"), alice).statusCode());
        attempt(alice, 1); attempt(bob, 0);
        var created = call(path, "POST", Json.obj("text", "<script>not executed</script>"), alice);
        assertEquals(200, created.statusCode()); String id = Json.text(data(created), "id");
        JsonNode publicRow = data(call(path, "GET", null, bob)).path("items").get(0);
        assertFalse(publicRow.has("authorId")); assertFalse(publicRow.has("moderationNote")); assertFalse(publicRow.path("owned").booleanValue());
        assertEquals(404, call("/api/discussions/" + id, "PUT", Json.obj("text", "forged", "revision", 1), bob).statusCode());
        assertEquals(409, call("/api/discussions/" + id, "PUT", Json.obj("text", "stale", "revision", 2), alice).statusCode());
        assertEquals(403, call("/api/discussions/" + id + "/moderation", "PATCH", Json.obj("revision", 1, "hidden", true, "note", "Not allowed."), alice).statusCode());
        var hidden = call("/api/discussions/" + id + "/moderation", "PATCH", Json.obj("revision", 1, "hidden", true, "note", "Avoid spoilers without explanation."), admin);
        assertEquals(200, hidden.statusCode()); assertTrue(data(hidden).path("hidden").booleanValue());
        assertEquals(0, data(call(path, "GET", null, bob)).path("items").size());
        assertEquals(1, data(call(path, "GET", null, admin)).path("items").size());
        assertEquals(403, call("/api/moderation/audit", "GET", null, bob).statusCode());
        String audit = Json.stringify(data(call("/api/moderation/audit", "GET", null, admin)));
        assertTrue(audit.contains("discussion.hidden")); assertFalse(audit.contains("<script>")); assertFalse(audit.contains("fixture@example"));
        assertEquals(200, call("/api/discussions/" + id, "DELETE", Json.obj("revision", 2), alice).statusCode());
    }
    @Test void votingIsOnePerAccountAndDoesNotBecomeAReferenceScore() throws Exception {
        Actor alice = actor(false), bob = actor(false); String path = "/api/community/drill:alias/vote";
        assertEquals(403, call(path, "PUT", Json.obj("value", 1), alice).statusCode());
        attempt(alice, 0); attempt(bob, 1);
        call(path, "PUT", Json.obj("value", 1), alice); call(path, "PUT", Json.obj("value", 1), alice);
        JsonNode value = data(call(path, "PUT", Json.obj("value", -1), bob));
        assertEquals(0, value.path("score").intValue()); assertEquals(1, value.path("up").intValue()); assertEquals(-1, value.path("mine").intValue());
        assertEquals(400, call(path, "PUT", Json.obj("value", 500), alice).statusCode());
        assertEquals(0, data(call(path, "PUT", Json.obj("value", 0), bob)).path("mine").intValue());
    }
    @Test void publicBoardIsOptInAndRepeatedAttemptsDoNotInflateItsScore() throws Exception {
        Actor alice = actor(false);
        JsonNode first = attempt(alice, 0), second = attempt(alice, 0);
        assertEquals(0, data(call("/api/leaderboard", "GET", null, null)).path("items").size());
        assertFalse(data(call("/api/leaderboard/preference", "GET", null, alice)).path("enabled").booleanValue());
        call("/api/leaderboard/preference", "PUT", Json.obj("enabled", true), alice);
        JsonNode board = data(call("/api/leaderboard", "GET", null, null));
        assertEquals(1, board.path("items").size()); assertEquals(1, board.path("items").get(0).path("curatedChecks").intValue());
        assertFalse(Json.stringify(board).contains("fixture@example")); assertFalse(Json.stringify(board).contains("userId"));
        JsonNode progress = data(call("/api/progress", "GET", null, alice));
        assertEquals("references", Json.text(progress.path("topics").get(0), "topic"));
        assertEquals(2, progress.path("topics").get(0).path("attempts").intValue());
        assertEquals(1, progress.path("topics").get(0).path("attemptedEntries").intValue());
        call("/api/attempts/" + Json.text(first, "id"), "DELETE", Json.obj(), alice);
        assertEquals(1, data(call("/api/leaderboard", "GET", null, null)).path("items").get(0).path("curatedChecks").intValue());
        call("/api/attempts/" + Json.text(second, "id"), "DELETE", Json.obj(), alice);
        assertEquals(0, data(call("/api/leaderboard", "GET", null, null)).path("items").size());
        call("/api/leaderboard/preference", "PUT", Json.obj("enabled", false), alice);
    }
    @Test void problemModerationAuditStoresMetadataNotPrivateReferences() throws Exception {
        Actor author = actor(false), admin = actor(true);
        ObjectNode problem = TestData.problem(); problem.put("title", "Audit fixture");
        var draft = data(call("/api/contributions", "POST", Json.obj("body", problem), author));
        String id = Json.text(draft, "id");
        var pending = data(call("/api/contributions/" + id + "/submit", "POST", Json.obj("revision", 1), author));
        assertEquals(2, pending.path("revision").intValue());
        assertEquals(200, call("/api/review/" + id, "POST", Json.obj("revision", 2, "decision", "publish", "note", "PRIVATE-REVIEW-NOTE", "confirmed", true), admin).statusCode());
        String audit = Json.stringify(data(call("/api/moderation/audit", "GET", null, admin)));
        assertTrue(audit.contains("problem.published")); assertFalse(audit.contains("PRIVATE-REVIEW-NOTE"));
        assertFalse(audit.contains("count = previous"));
    }
}
