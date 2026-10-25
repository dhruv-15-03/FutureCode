package io.futurecode;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import java.util.Map;

@Component
public final class ProblemDirectory {
    public record Entry(String key, String title, String topic, String topicLabel, String category) {}
    private static final Map<String, String> LABELS = Map.of(
        "references", "References and mutation", "control-flow", "Control flow", "hashing", "Hashing and identity",
        "boundaries", "Boundary testing", "concurrency", "Concurrency", "reading", "Community reading",
        "debugging", "Community debugging", "test-design", "Community test design", "ai-review", "Community AI review",
        "case-study", "Community engineering cases");
    private final CuratedContent content;
    public ProblemDirectory(CuratedContent content) { this.content = content; }
    public Entry resolve(String key, Store store) {
        if (!Json.bounded(key, 80, true)) throw ApiError.missing();
        for (JsonNode item : content.catalog()) if (Json.text(item, "key").equals(key)) {
            String topic = switch (key) {
                case "drill:alias" -> "references";
                case "drill:finally" -> "control-flow";
                case "drill:cache" -> "hashing";
                case "drill:boundary" -> "boundaries";
                default -> "concurrency";
            };
            return new Entry(key, Json.text(item, "title"), topic, LABELS.get(topic), "curated");
        }
        if (key.startsWith("community:") && Json.uuid(key.substring(10))) {
            JsonNode problem = store.getProblem(key.substring(10));
            if (!Json.text(problem, "status").equals("published")) throw ApiError.missing();
            String topic = Json.text(problem, "kind");
            return new Entry(key, Json.text(problem, "title"), topic, LABELS.get(topic), "community");
        }
        throw ApiError.missing();
    }
    public String topicLabel(String topic) {
        String value = LABELS.get(topic);
        if (value == null) throw new IllegalArgumentException("Unknown reading topic.");
        return value;
    }
}
