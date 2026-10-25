package io.futurecode;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Service
public final class GradingService {
    private final CuratedContent content;
    public GradingService(CuratedContent content) { this.content = content; }
    private static void invalid(String message) { throw new ApiError(400, message, "invalid_attempt"); }
    private static ObjectNode saved(String key, String title, String version, String category, Integer score, Integer max, JsonNode record) {
        return Json.obj("problemKey", key, "title", title, "version", version, "category", category, "score", score, "maxScore", max, "record", record);
    }

    public ObjectNode grade(JsonNode input, Store store) {
        if (!Json.exact(input, "problemKey", "payload") || !Json.bounded(input.get("problemKey"), 80, true) || !input.path("payload").isObject()) {
            invalid("Submit a valid attempt. API keys and coach conversations are not accepted in saved attempts.");
        }
        String key = Json.text(input, "problemKey");
        JsonNode payload = input.path("payload");
        if (key.startsWith("drill:")) {
            JsonNode challenge = null;
            for (JsonNode candidate : content.challenges()) if (key.equals("drill:" + Json.text(candidate, "id"))) challenge = candidate;
            int answer = Json.integer(payload, "answer", -1);
            if (challenge == null || !Json.exact(payload, "answer") || answer < 0 || answer > 3) invalid("Choose one answer for an existing curated drill.");
            JsonNode question = challenge.path("questions").get(0);
            boolean matched = answer == Json.integer(question, "correctIndex", -1);
            ObjectNode record = Json.obj("answer", answer, "correctIndex", question.path("correctIndex"), "explanation", question.path("explanation"),
                "lines", question.path("lines"), "grading", "Curated reference comparison, not a hiring assessment.");
            return Json.obj("attempt", saved(key, Json.text(challenge, "title"), Crypto.sha(Json.stringify(challenge)), "curated", matched ? 1 : 0, 1, record),
                "feedback", Json.obj("matched", matched, "correctIndex", question.path("correctIndex"), "explanation", question.path("explanation"), "lines", question.path("lines")));
        }
        if (key.equals("case:" + Json.text(content.practiceCase(), "id"))) return gradeCase(key, payload);
        if (key.startsWith("community:") && Json.uuid(key.substring(10))) {
            ObjectNode problem = store.getProblem(key.substring(10));
            if (!Json.text(problem, "status").equals("published")) throw new ApiError(404, "Published problem not found.", "not_found");
            boolean choice = Json.text(problem.path("body"), "responseMode").equals("choice");
            if (!Json.exact(payload, "revision", "answer", "explanation") || Json.integer(payload, "revision", -1) < 1 || !Json.bounded(payload.get("explanation"), 4000, !choice)) invalid("Provide a bounded response to the published problem.");
            if (Json.integer(payload, "revision", -1) != Json.integer(problem, "revision", -1)) throw new ApiError(409, "The problem version changed. Reload it before submitting.", "revision_conflict");
            int answer = Json.integer(payload, "answer", -1);
            if (choice ? answer < 0 || answer > 3 : !payload.path("answer").isNull()) invalid("The response does not match this problem type.");
            Boolean matched = choice ? answer == Json.integer(problem.path("body"), "correctIndex", -1) : null;
            ObjectNode record = Json.obj("answer", payload.path("answer"), "explanation", payload.path("explanation"), "referenceAnswer", problem.path("body").path("correctIndex"),
                "referenceRevision", problem.path("revision"), "grading", choice ? "Agreement with a community author reference, not verified semantic correctness." : "Written community response; no automatic correctness score.");
            return Json.obj("attempt", saved(key, Json.text(problem, "title"), problem.path("revision").asString(), choice ? "community" : "ungraded", choice ? (Boolean.TRUE.equals(matched) ? 1 : 0) : null, choice ? 1 : null, record),
                "feedback", Json.obj("matched", matched, "proposedAnswer", problem.path("body").path("correctIndex"), "reference", problem.path("body").path("reference"), "requiresHumanReview", true));
        }
        throw new ApiError(400, "Choose an existing FutureCode problem.", "invalid_attempt");
    }

    private ObjectNode gradeCase(String key, JsonNode payload) {
        JsonNode artifacts = payload.path("artifacts"), selections = payload.path("selections"), limits = content.limits().path("artifacts");
        if (!Json.exact(payload, "artifacts", "selections", "pins", "schedule", "elapsedSeconds", "hintsViewed") ||
            !Json.exact(artifacts, "flow", "questions", "critique", "patch", "patchReasoning", "testPlan", "verification") ||
            !Json.exact(selections, "aiDecision", "invariant", "expectedCount") ||
            !payload.path("pins").isArray() || payload.path("pins").size() > content.limits().path("caseMaxPins").intValue() ||
            !payload.path("schedule").isArray() || Json.integer(payload, "elapsedSeconds", -1) < 0 || Json.integer(payload, "elapsedSeconds", -1) > 36000 ||
            Json.integer(payload, "hintsViewed", -1) < 0 || Json.integer(payload, "hintsViewed", -1) > content.practiceCase().path("hints").size()) invalid("The case attempt is incomplete or outside its limits.");
        for (var field : limits.properties()) if (!Json.bounded(artifacts.get(field.getKey()), field.getValue().intValue(), true)) invalid("The case attempt is incomplete or outside its limits.");
        Set<String> pins = new HashSet<>(), files = new HashSet<>();
        for (JsonNode pin : payload.path("pins")) {
            if (!Json.exact(pin, "file", "line")) invalid("An evidence pin is invalid.");
            JsonNode file = content.file(Json.text(pin, "file"));
            String[] lines = file == null ? new String[0] : Json.lines(Json.text(file, "source"));
            int line = Json.integer(pin, "line", -1);
            if (line < 1 || line > lines.length || Json.trim(lines[line - 1]).isEmpty()) invalid("Evidence pins must point to real nonblank lines in this case.");
            if (!pins.add(Json.text(pin, "file") + "#" + line)) invalid("Evidence pins must be unique.");
            files.add(Json.text(pin, "file"));
        }
        if (files.size() < 2) invalid("Pin evidence from at least two different source files. This checks coverage, not relevance.");
        ObjectNode trace = trace(payload.path("schedule"));
        if (!trace.path("lost").booleanValue()) invalid("Find a four-operation schedule that loses an increment. Reset a serial trace and try overlapping the reads.");
        if (!Set.of("accept", "reject").contains(Json.text(selections, "aiDecision"))) invalid("Commit an accept/reject decision about the seeded proposal.");
        if (Json.trim(Json.text(artifacts, "patch")).equals(Json.trim(Json.text(content.file("RequestCounter.java"), "source")))) invalid("Edit the proposed RequestCounter.java patch. Edits are recorded, not executed or automatically graded.");
        if (!Set.of("atomic", "container", "report-lock", "best-effort").contains(Json.text(selections, "invariant"))) invalid("Choose the invariant your fix must guarantee.");
        if (!Json.text(selections, "expectedCount").matches("[0-2]")) invalid("Enter a final count of 0, 1, or 2 for two completed requests.");
        if (Json.bytes(payload).length > 24000) invalid("The complete attempt exceeds 24 KB. Shorten the written artifacts before saving.");
        ArrayNode checks = Json.arr();
        checks.add(Json.obj("label", "Controlled lost-update schedule", "passed", true, "detail", "Two reads of 0 followed by two writes of 1 lose one completed request."));
        checks.add(Json.obj("label", "Seeded proposal decision", "passed", Json.text(selections, "aiDecision").equals("reject"), "detail", "Reject the proposal: a failed conditional replacement is ignored instead of retried."));
        checks.add(Json.obj("label", "Required fix invariant", "passed", Json.text(selections, "invariant").equals("atomic"), "detail", "Each completed request must apply one atomic increment for its route."));
        checks.add(Json.obj("label", "Concurrent regression expectation", "passed", Json.text(selections, "expectedCount").equals("2"), "detail", "After two completed calls, the required final count is 2, not 1."));
        int score = 0;
        for (JsonNode check : checks) if (check.path("passed").booleanValue()) score++;
        ObjectNode record = Json.obj("artifacts", artifacts, "selections", selections, "pins", payload.path("pins"), "controlledTrace", trace, "schedule", payload.path("schedule"),
            "elapsedSeconds", payload.path("elapsedSeconds"), "elapsedIsSelfReported", true, "hintsViewed", payload.path("hintsViewed"), "checks", checks, "requiresHumanReview", true,
            "grading", "Only four narrow curated checks are scored. Written code, explanations and tests were not executed or semantically graded. Coach conversations and keys are not stored.");
        return Json.obj("attempt", saved(key, Json.text(content.practiceCase(), "title"), Crypto.sha(Json.stringify(content.practiceCase().path("files"))), "curated", score, 4, record),
            "feedback", Json.obj("checks", checks, "requiresHumanReview", true));
    }

    public static ObjectNode trace(JsonNode schedule) {
        if (!schedule.isArray() || schedule.size() > 4) invalid("The controlled trace is not a legal read-before-write schedule.");
        Map<String, Integer> reads = new HashMap<>();
        Set<String> writes = new HashSet<>();
        ArrayNode steps = Json.arr();
        int value = 0;
        for (JsonNode step : schedule) {
            String operation = Json.string(step);
            if (!Set.of("A:read", "A:write", "B:read", "B:write").contains(operation)) invalid("The controlled trace is not a legal read-before-write schedule.");
            String thread = operation.substring(0, 1);
            String text;
            if (operation.endsWith(":read")) {
                if (reads.putIfAbsent(thread, value) != null) invalid("The controlled trace is not a legal read-before-write schedule.");
                text = thread + " reads " + value;
            } else {
                if (!reads.containsKey(thread) || !writes.add(thread)) invalid("The controlled trace is not a legal read-before-write schedule.");
                value = reads.get(thread) + 1;
                text = thread + " writes " + value + " from its captured " + reads.get(thread);
            }
            steps.add(Json.obj("operation", operation, "text", text, "value", value));
        }
        return Json.obj("steps", steps, "value", value, "completed", writes.size(), "finished", schedule.size() == 4, "lost", schedule.size() == 4 && value < 2);
    }
}
