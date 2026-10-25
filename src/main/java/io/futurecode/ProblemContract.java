package io.futurecode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class ProblemContract {
    public static final Set<String> DIFFICULTIES = Set.of("introductory", "intermediate", "advanced");
    private static final Set<String> KINDS = Set.of("reading", "debugging", "test-design", "ai-review", "case-study");
    private ProblemContract() {}
    public static void validate(JsonNode value, boolean complete) {
        String issue = issue(value, complete);
        if (!issue.isEmpty()) throw ApiError.bad(issue);
    }
    public static ObjectNode publicBody(JsonNode value) {
        return Json.fields(value, "title", "statement", "language", "difficulty", "kind", "files", "responseMode", "options");
    }
    public static String issue(JsonNode value, boolean complete) {
        if (!Json.exact(value, "title", "statement", "language", "difficulty", "kind", "files", "responseMode", "options", "correctIndex", "reference", "rightsConfirmed")) return "The problem has an invalid structure. Reload the editor and retry.";
        if (!Json.bounded(value.get("title"), 120, true) || Json.trim(Json.text(value, "title")).length() < 3) return "Give the problem a title of 3-120 characters.";
        if (!Json.bounded(value.get("statement"), 5000, complete)) return "Write the problem statement (up to 5,000 characters).";
        if (!Json.bounded(value.get("language"), 32, true) || !DIFFICULTIES.contains(Json.text(value, "difficulty")) || !KINDS.contains(Json.text(value, "kind"))) return "Choose a language, difficulty, and problem type.";
        JsonNode files = value.path("files");
        if (!files.isArray() || files.size() < 1 || files.size() > 6) return "Include 1-6 source files.";
        Set<String> names = new HashSet<>();
        int sourceBytes = 0;
        for (JsonNode file : files) {
            String name = Json.text(file, "name");
            if (!Json.exact(file, "name", "source") || !name.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}") || name.contains("..") ||
                !names.add(name.toLowerCase(Locale.ROOT)) || !Json.bounded(file.get("source"), 12000, complete) || Json.lines(Json.text(file, "source")).length > 400) {
                return "Use unique plain file names, at most 12,000 characters and 400 lines per file, with no binary content.";
            }
            sourceBytes += Json.text(file, "source").getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
        if (sourceBytes > 24000) return "Source files together exceed the 24 KB UTF-8 limit. Use a smaller excerpt.";
        String mode = Json.text(value, "responseMode");
        JsonNode options = value.path("options");
        if (!Set.of("choice", "written").contains(mode) || !options.isArray()) return "Choose a multiple-choice or written response.";
        int correct = Json.integer(value, "correctIndex", -1);
        if (mode.equals("choice")) {
            if (options.size() != 4) return "Multiple-choice problems need four options, up to 500 characters each.";
            for (JsonNode option : options) if (!Json.bounded(option, 500, complete)) return "Multiple-choice problems need four options, up to 500 characters each.";
            Set<String> distinct = new HashSet<>();
            for (JsonNode option : options) distinct.add(Json.trim(option.asString()));
            if (complete && (distinct.size() != 4 || correct < 0 || correct > 3)) return "Provide four distinct options and select your proposed reference answer.";
            if (!complete && !value.path("correctIndex").isNull() && (correct < 0 || correct > 3)) return "The reference answer index is invalid.";
        } else if (!options.isEmpty() || !value.path("correctIndex").isNull()) return "Written responses do not have multiple-choice options or an automatic score.";
        JsonNode reference = value.path("reference");
        if (!Json.exact(reference, "explanation", "solution", "verification", "evidence") ||
            !Json.bounded(reference.get("explanation"), 4000, complete) || !Json.bounded(reference.get("solution"), 6000, false) ||
            !Json.bounded(reference.get("verification"), 2000, complete) || !reference.path("evidence").isArray() ||
            reference.path("evidence").size() > 6 || (complete && reference.path("evidence").isEmpty())) {
            return "Include a reference explanation, a verification suggestion, and 1-6 evidence citations before submitting for review. A code solution is optional.";
        }
        Set<String> cited = new HashSet<>();
        for (JsonNode citation : reference.path("evidence")) {
            if (!Json.exact(citation, "file", "line", "quote")) return "An evidence citation has an invalid structure.";
            JsonNode file = null;
            for (JsonNode candidate : files) if (Json.text(candidate, "name").equals(Json.text(citation, "file"))) file = candidate;
            String[] lines = file == null ? new String[0] : Json.lines(Json.text(file, "source"));
            int line = Json.integer(citation, "line", -1);
            if (line < 1 || line > lines.length || !Json.bounded(citation.get("quote"), 12000, true) ||
                !Json.text(citation, "quote").equals(Json.trim(lines[line - 1])) || !cited.add(Json.text(citation, "file") + "#" + line)) {
                return "Each evidence quote must match its exact, nonblank source line. Remove or reselect stale citations after editing source.";
            }
        }
        if (!value.path("rightsConfirmed").isBoolean() || (complete && !value.path("rightsConfirmed").booleanValue())) return "Confirm you have permission to publish the source and solution and have removed secrets.";
        if (Json.bytes(value).length > 48000) return "The complete problem exceeds 48 KB. Shorten its source or reference solution.";
        return "";
    }
}
