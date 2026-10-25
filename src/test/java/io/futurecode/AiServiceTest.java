package io.futurecode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;

class AiServiceTest {
    private static final String KEY = "sk-test-not-a-live-secret";
    private final CuratedContent content = new CuratedContent();
    AiServiceTest() throws java.io.IOException {}
    private static final class StubHttp extends BoundedHttp {
        int calls;
        JsonNode reply;
        JsonNode sent;
        int status = 200;
        Failure failure;
        StubHttp(JsonNode reply) { this.reply = reply; }
        @Override public Response request(URI uri, Map<String, String> headers, byte[] body, int max, Duration timeout) {
            calls++; assertEquals(OPENAI, uri); assertEquals("Bearer " + KEY, headers.get("Authorization")); assertEquals(48000, max); assertEquals(Duration.ofSeconds(25), timeout);
            sent = Json.parse(body); assertFalse(new String(body, java.nio.charset.StandardCharsets.UTF_8).contains(KEY));
            if (failure != null) throw failure;
            if (status != 200) return new Response(status, ("PRIVATE-PROVIDER-BODY " + KEY).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new Response(200, Json.bytes(Json.obj("choices", List.of(Json.obj("finish_reason", "stop", "message", Json.obj("content", Json.stringify(reply)))))));
        }
    }
    private ObjectNode input() { return Json.obj("source", TestData.samples().path("source"), "key", KEY, "consent", true); }
    @Test void fixesDestinationModelAndSchemaAndSendsSourceAsUntrustedDataWithNoTools() {
        try (StubHttp http = new StubHttp(TestData.samples().path("exercise"))) {
            JsonNode reply = new AiService(content, http).exercise(input());
            assertEquals(3, reply.path("questions").size()); assertEquals(1, http.calls);
            assertEquals("gpt-4o-mini", Json.text(http.sent, "model")); assertEquals(3600, Json.integer(http.sent, "max_tokens", 0));
            assertTrue(http.sent.path("response_format").path("json_schema").path("strict").booleanValue()); assertFalse(http.sent.has("tools"));
            assertTrue(Json.text(http.sent.path("messages").get(0), "content").contains("not an executed result"));
            JsonNode context = Json.parse(Json.text(http.sent.path("messages").get(1), "content"));
            assertTrue(Json.text(context, "untrusted_source").startsWith("1 | function total"));
        }
    }
    @ParameterizedTest @ValueSource(strings = {"no-consent", "short-key", "header-key", "empty", "oversize", "binary", "too-many-lines", "extra-provider", "bad-unicode"})
    void rejectsUnsafeInputBeforeAnyProviderCall(String variant) {
        ObjectNode input = input();
        switch (variant) {
            case "no-consent" -> input.put("consent", false);
            case "short-key" -> input.put("key", "short");
            case "header-key" -> input.put("key", KEY + "\r\nheader");
            case "empty" -> input.put("source", "");
            case "oversize" -> input.put("source", "x".repeat(12001));
            case "binary" -> input.put("source", "x\0");
            case "too-many-lines" -> input.put("source", "x\n".repeat(400));
            case "extra-provider" -> input.put("provider", "https://evil.test");
            case "bad-unicode" -> input.put("source", "\ud800");
        }
        try (StubHttp http = new StubHttp(TestData.samples().path("exercise"))) {
            assertEquals(400, assertThrows(ApiError.class, () -> new AiService(content, http).exercise(input)).status());
            assertEquals(0, http.calls);
        }
    }
    @ParameterizedTest @ValueSource(strings = {"extra", "missing", "duplicate-kind", "duplicate-prompt", "duplicate-option", "fraction-answer", "bad-quote", "wrong-line", "no-evidence", "blank-assumption"})
    void refusesMalformedOrFabricatedExerciseReferences(String variant) {
        ObjectNode reply = (ObjectNode) TestData.samples().path("exercise").deepCopy();
        ObjectNode question = (ObjectNode) reply.path("questions").get(0);
        switch (variant) {
            case "extra" -> reply.put("unexpected", true);
            case "missing" -> question.remove("verification");
            case "duplicate-kind" -> ((ObjectNode) reply.path("questions").get(1)).put("kind", "trace");
            case "duplicate-prompt" -> ((ObjectNode) reply.path("questions").get(1)).set("prompt", question.path("prompt"));
            case "duplicate-option" -> question.set("options", Json.node(List.of("a", " a ", "c", "d")));
            case "fraction-answer" -> question.put("correctIndex", 1.2);
            case "bad-quote" -> ((ObjectNode) question.path("evidence").get(0)).put("quote", "fabricated();");
            case "wrong-line" -> ((ObjectNode) question.path("evidence").get(0)).put("line", 0);
            case "no-evidence" -> question.set("evidence", Json.arr());
            case "blank-assumption" -> reply.set("assumptions", Json.node(List.of("")));
        }
        assertEquals(502, assertThrows(ApiError.class, () -> AiService.validateExercise(reply, Json.text(TestData.samples(), "source"))).status());
    }
    @ParameterizedTest @ValueSource(strings = {"\n", "\r\n", "\r"}) void citationMatchingSupportsAllLineEndingsWithoutClaimingSemanticProof(String ending) {
        JsonNode reply = TestData.samples().path("exercise");
        assertDoesNotThrow(() -> AiService.validateExercise(reply, Json.text(TestData.samples(), "source").replace("\n", ending)));
        ObjectNode uncertain = (ObjectNode) reply.deepCopy();
        ((ObjectNode) uncertain.path("questions").get(0)).put("correctIndex", 1);
        assertDoesNotThrow(() -> AiService.validateExercise(uncertain, Json.text(TestData.samples(), "source")));
    }
    @ParameterizedTest @ValueSource(ints = {401, 429, 403, 500, 302}) void sanitizesProviderStatusBodiesAndNeverRetries(int upstream) {
        try (StubHttp http = new StubHttp(TestData.samples().path("exercise"))) {
            http.status = upstream;
            ApiError error = assertThrows(ApiError.class, () -> new AiService(content, http).exercise(input()));
            assertEquals(upstream == 401 || upstream == 429 ? upstream : 502, error.status());
            assertFalse(error.getMessage().contains(KEY)); assertFalse(error.getMessage().contains("PRIVATE")); assertEquals(1, http.calls);
        }
    }
    @Test void coachReceivesOnlyCuratedContextAndExplicitConversationAndRejectsStaleCitations() {
        try (StubHttp http = new StubHttp(TestData.samples().path("coach"))) {
            AiService service = new AiService(content, http);
            JsonNode input = Json.obj("caseId", "missing-requests-v1", "messages", List.of(Json.obj("role", "user", "content", "How can I check the proposed fix?")), "key", KEY, "consent", true);
            service.coach(input);
            assertEquals(1400, Json.integer(http.sent, "max_tokens", 0));
            JsonNode context = Json.parse(Json.text(http.sent.path("messages").get(1), "content"));
            assertEquals(5, context.path("curated_case").path("files").size()); assertFalse(context.path("curated_case").has("reference"));
            ObjectNode invalid = (ObjectNode) TestData.samples().path("coach").deepCopy();
            ((ObjectNode) invalid.path("citations").get(0)).put("quote", "fabricated();");
            assertEquals(502, assertThrows(ApiError.class, () -> service.validateCoach(invalid)).status());
        }
    }
    @Test void oversizedStreamIsCancelledBeforeItCanBufferMoreThanItsLimit() {
        BoundedHttp.LimitedBody subscriber = new BoundedHttp.LimitedBody(4);
        boolean[] cancelled = {false};
        subscriber.onSubscribe(new Flow.Subscription() { public void request(long count) {} public void cancel() { cancelled[0] = true; } });
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[5])));
        assertTrue(cancelled[0]); assertTrue(subscriber.getBody().toCompletableFuture().isCompletedExceptionally());
    }
    @Test void outboundDestinationsAreNotConfigurableAndTimeoutFailuresAreExplicit() {
        try (BoundedHttp http = new BoundedHttp()) {
            assertThrows(IllegalArgumentException.class, () -> http.request(URI.create("https://evil.test"), Map.of(), null, 100, Duration.ofSeconds(1)));
        }
        try (StubHttp http = new StubHttp(TestData.samples().path("exercise"))) {
            http.failure = new BoundedHttp.Failure(BoundedHttp.Failure.Kind.TIMEOUT);
            assertTrue(assertThrows(ApiError.class, () -> new AiService(content, http).exercise(input())).getMessage().contains("timed out"));
            assertEquals(1, http.calls);
        }
    }
}
