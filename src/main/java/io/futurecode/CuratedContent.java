package io.futurecode;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import java.io.IOException;

@Component
public final class CuratedContent {
    private final JsonNode data;
    private final ArrayNode catalog = Json.arr();
    public CuratedContent() throws IOException {
        try (var input = new ClassPathResource("content/curated.json").getInputStream()) {
            data = Json.MAPPER.readTree(input);
        }
        catalog.add(Json.obj("key", "case:" + Json.text(practiceCase(), "id"), "title", practiceCase().path("title"), "language", "Java",
            "difficulty", "intermediate", "kind", "case-study", "duration", "20-30 min", "provenance", "Curated case", "href", "#interview",
            "description", "Five files, one lost-update incident, and an AI proposal to investigate."));
        for (JsonNode challenge : challenges()) {
            String id = Json.text(challenge, "id");
            catalog.add(Json.obj("key", "drill:" + id, "title", challenge.path("title"), "language", "Java",
                "difficulty", switch (Json.text(challenge, "level")) { case "Warm-up" -> "introductory"; case "Advanced" -> "advanced"; default -> "intermediate"; },
                "kind", switch (id) { case "patch" -> "ai-review"; case "boundary" -> "test-design"; case "cache" -> "debugging"; default -> "reading"; },
                "duration", challenge.path("time"), "provenance", "Curated reference", "href", "#library/" + id, "description", challenge.path("concept")));
        }
    }
    public JsonNode challenges() { return data.path("challenges"); }
    public JsonNode practiceCase() { return data.path("practiceCase"); }
    public JsonNode exerciseSchema() { return data.path("exerciseSchema"); }
    public JsonNode coachSchema() { return data.path("coachSchema"); }
    public JsonNode limits() { return data.path("limits"); }
    JsonNode manifest() { return data; }
    public ArrayNode catalog() { return catalog.deepCopy(); }
    public JsonNode file(String name) {
        for (JsonNode file : practiceCase().path("files")) if (Json.text(file, "name").equals(name)) return file;
        return null;
    }
}
