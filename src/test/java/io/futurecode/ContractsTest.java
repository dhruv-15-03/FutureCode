package io.futurecode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ContractsTest {
    private final CuratedContent content = new CuratedContent();
    ContractsTest() throws java.io.IOException {}
    @Test void curatedAnswersAndServerComputedScoresMatchTheBrowser() {
        GradingService grading = new GradingService(content);
        int[] expected = {0, 2, 1, 2, 1};
        assertEquals(6, content.catalog().size());
        for (int index = 0; index < expected.length; index++) {
            JsonNode challenge = content.challenges().get(index);
            ObjectNode result = grading.grade(Json.obj("problemKey", "drill:" + Json.text(challenge, "id"), "payload", Json.obj("answer", expected[index])), null);
            assertEquals(1, result.path("attempt").path("score").intValue());
            assertEquals(64, Json.text(result.path("attempt"), "version").length());
        }
        assertThrows(ApiError.class, () -> grading.grade(Json.obj("problemKey", "drill:alias", "payload", Json.obj("answer", 1, "score", 1)), null));
    }
    @Test void caseScoresOnlyFourNarrowChecksAndDoesNotExecuteOrGradeWrittenCode() {
        ObjectNode result = new GradingService(content).grade(Json.obj("problemKey", "case:missing-requests-v1", "payload", TestData.casePayload()), null);
        assertEquals(4, result.path("attempt").path("score").intValue());
        assertTrue(result.path("attempt").path("record").path("requiresHumanReview").booleanValue());
        assertEquals("Not executable candidate text.", Json.text(result.path("attempt").path("record").path("artifacts"), "patch"));
    }
    @ParameterizedTest @ValueSource(strings = {"key", "score", "coach", "authorId"}) void rejectsExtraSavedCaseFields(String field) {
        ObjectNode payload = TestData.casePayload(); payload.put(field, "not-accepted");
        ApiError error = assertThrows(ApiError.class, () -> new GradingService(content).grade(Json.obj("problemKey", "case:missing-requests-v1", "payload", payload), null));
        assertEquals(400, error.status());
    }
    @ParameterizedTest @ValueSource(strings = {"duplicate", "blank", "missing-file", "illegal-schedule", "serial", "unchanged-patch", "numeric-count"})
    void rejectsInvalidCaseArtifacts(String variant) {
        ObjectNode payload = TestData.casePayload();
        switch (variant) {
            case "duplicate" -> payload.set("pins", Json.node(List.of(Json.obj("file", "RequestCounter.java", "line", 16), Json.obj("file", "RequestCounter.java", "line", 16))));
            case "blank" -> payload.set("pins", Json.node(List.of(Json.obj("file", "RequestController.java", "line", 3))));
            case "missing-file" -> payload.set("pins", Json.node(List.of(Json.obj("file", ".env", "line", 1))));
            case "illegal-schedule" -> payload.set("schedule", Json.node(List.of("A:write", "A:read", "B:read", "B:write")));
            case "serial" -> payload.set("schedule", Json.node(List.of("A:read", "A:write", "B:read", "B:write")));
            case "unchanged-patch" -> ((ObjectNode) payload.path("artifacts")).put("patch", Json.text(content.file("RequestCounter.java"), "source"));
            case "numeric-count" -> ((ObjectNode) payload.path("selections")).put("expectedCount", 2);
        }
        assertThrows(ApiError.class, () -> new GradingService(content).grade(Json.obj("problemKey", "case:missing-requests-v1", "payload", payload), null));
    }
    @Test void communityContractSeparatesDraftsWrittenWorkAndPublicReferences() {
        ObjectNode problem = TestData.problem();
        assertEquals("", ProblemContract.issue(problem, true));
        ObjectNode publicBody = ProblemContract.publicBody(problem);
        for (String privateField : List.of("correctIndex", "reference", "rightsConfirmed")) assertFalse(publicBody.has(privateField));
        problem.put("responseMode", "written"); problem.set("options", Json.arr()); problem.putNull("correctIndex");
        assertEquals("", ProblemContract.issue(problem, true));
        problem.put("correctIndex", 0); assertFalse(ProblemContract.issue(problem, true).isEmpty());
    }
    @ParameterizedTest @ValueSource(strings = {"extra-role", "rights", "duplicates", "bad-answer", "bad-path", "duplicate-file", "binary", "surrogate", "too-many-lines", "stale-citation"})
    void rejectsMalformedCommunityProblems(String variant) {
        ObjectNode problem = TestData.problem();
        switch (variant) {
            case "extra-role" -> problem.put("role", "admin");
            case "rights" -> problem.put("rightsConfirmed", false);
            case "duplicates" -> problem.set("options", Json.node(List.of("same", "same", "same", "same")));
            case "bad-answer" -> problem.put("correctIndex", 4);
            case "bad-path" -> problem.set("files", Json.node(List.of(Json.obj("name", "../../.env", "source", "secret"))));
            case "duplicate-file" -> problem.set("files", Json.node(List.of(Json.obj("name", "a.java", "source", "a"), Json.obj("name", "A.java", "source", "b"))));
            case "binary" -> problem.put("statement", "x\0");
            case "surrogate" -> problem.put("statement", "malformed \ud800");
            case "too-many-lines" -> problem.set("files", Json.node(List.of(Json.obj("name", "a.java", "source", "x\n".repeat(401)))));
            case "stale-citation" -> ((ObjectNode) problem.path("reference")).set("evidence", Json.node(List.of(Json.obj("file", "Counter.java", "line", 2, "quote", "invented();"))));
        }
        assertFalse(ProblemContract.issue(problem, true).isEmpty());
    }
    @ParameterizedTest @ValueSource(strings = {"", "{", "{\"x\":1} {\"y\":2}", "{\"x\":1,\"x\":2}", "[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[[0]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]]"})
    void jsonParserRejectsAmbiguousOrExcessivelyDeepInput(String json) { assertThrows(ApiError.class, () -> Json.parse(json)); }
    @Test void limiterIsBoundedAndRefusesToForgetActiveAccountsToAdmitAttackTraffic() {
        BoundedLimiter limiter = new BoundedLimiter(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), 2);
        limiter.consume("one", 1, 1000);
        assertEquals(429, assertThrows(ApiError.class, () -> limiter.consume("one", 1, 1000)).status());
        limiter.consume("two", 1, 1000);
        assertEquals(503, assertThrows(ApiError.class, () -> limiter.consume("three", 1, 1000)).status());
    }
    @Test void configurationDoesNotRevealSecretsAndDecodesMySqlCredentialsWithoutQueryOverrides() {
        AppSettings settings = TestData.settings(true);
        assertEquals("fixture", settings.database().user());
        assertFalse(settings.toString().contains(settings.sessionSecret()));
        assertFalse(settings.database().toString().contains("unused"));
        assertFalse(TestData.settings(false).authConfigured());
    }
}
