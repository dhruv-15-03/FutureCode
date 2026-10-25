package io.futurecode;

import tools.jackson.databind.JsonNode;
import java.net.URI;
import java.util.Arrays;
import java.util.stream.Stream;

public enum AiProvider {
    OPENAI("openai", "OpenAI", "gpt-4o-mini", BoundedHttp.OPENAI),
    GEMINI("gemini", "Google Gemini", "gemini-3.8-flash", BoundedHttp.GEMINI);

    private final String id, label, model;
    private final URI endpoint;
    AiProvider(String id, String label, String model, URI endpoint) { this.id = id; this.label = label; this.model = model; this.endpoint = endpoint; }
    public String id() { return id; }
    public String label() { return label; }
    public String model() { return model; }
    public URI endpoint() { return endpoint; }
    public static AiProvider from(JsonNode input) {
        if (!input.has("provider")) return OPENAI;
        return Arrays.stream(values()).filter(provider -> provider.id.equals(Json.text(input, "provider"))).findFirst()
            .orElseThrow(() -> ApiError.bad("Choose OpenAI or Google Gemini; custom provider URLs are not accepted."));
    }
    public static boolean keys(JsonNode input, String... keys) {
        return input != null && Json.exact(input, input.has("provider") ? Stream.concat(Arrays.stream(keys), Stream.of("provider")).toArray(String[]::new) : keys);
    }
}
