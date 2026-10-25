package io.futurecode;

import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.List;

final class TestData {
    private TestData() {}
    static AppSettings settings(boolean oauth) {
        return new AppSettings("serve", "", "development", "http://127.0.0.1:4318", "127.0.0.1",
            oauth ? "mysql://fixture:unused@127.0.0.1/futurecode_test" : "", "", "disabled", "", 4,
            oauth ? "fixture.apps.googleusercontent.com" : "", oauth ? "fixture-not-a-live-secret" : "",
            oauth ? "fixture-only-session-secret-not-for-production-12345" : "", 200, 500, 10, 20, 8);
    }
    static JsonNode samples() {
        try (var input = new ClassPathResource("samples.json").getInputStream()) { return Json.MAPPER.readTree(input); }
        catch (IOException error) { throw new IllegalStateException("Shared test samples are missing."); }
    }
    static ObjectNode problem() {
        return Json.obj("title", "A shared counter", "statement", "What can this operation lose under overlap?", "language", "Java",
            "difficulty", "intermediate", "kind", "reading", "files", List.of(Json.obj("name", "Counter.java", "source", "int previous = count;\ncount = previous + 1;")),
            "responseMode", "choice", "options", List.of("An increment", "A file", "A thread object", "Nothing"), "correctIndex", 0, "rightsConfirmed", true,
            "reference", Json.obj("explanation", "Two requests can read the same old count.", "solution", "Use an atomic update.",
                "verification", "Force both reads before either write and check that two increments are preserved.",
                "evidence", List.of(Json.obj("file", "Counter.java", "line", 2, "quote", "count = previous + 1;"))));
    }
    static ObjectNode casePayload() {
        return Json.obj("artifacts", Json.obj("flow", "The request completes through finally into a shared counter.", "questions", "Assume shared state.",
                "critique", "Ignore a failed replacement and one update is lost.", "patch", "Not executable candidate text.",
                "patchReasoning", "Atomic per-route update.", "testPlan", "Force both reads before writes, assert 2.", "verification", "Compile and review independently."),
            "selections", Json.obj("aiDecision", "reject", "invariant", "atomic", "expectedCount", "2"),
            "pins", List.of(Json.obj("file", "RequestController.java", "line", 12), Json.obj("file", "RequestCounter.java", "line", 16)),
            "schedule", List.of("A:read", "B:read", "A:write", "B:write"), "elapsedSeconds", 100, "hintsViewed", 0);
    }
}
