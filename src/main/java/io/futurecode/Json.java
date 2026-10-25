package io.futurecode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class Json {
    public static final JsonMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).maxStringLength(65000).maxNumberLength(20).build()).build())
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private Json() {}

    public static ObjectNode obj(Object... pairs) {
        ObjectNode result = MAPPER.createObjectNode();
        for (int index = 0; index < pairs.length; index += 2) result.set((String) pairs[index], node(pairs[index + 1]));
        return result;
    }
    public static ArrayNode arr() { return MAPPER.createArrayNode(); }
    public static JsonNode node(Object value) { return value instanceof JsonNode json ? json : MAPPER.valueToTree(value); }
    public static JsonNode parse(byte[] value) {
        try {
            JsonNode result = MAPPER.readTree(value);
            if (result == null || result.isMissingNode()) throw ApiError.bad("Request must contain valid JSON.");
            return result;
        }
        catch (RuntimeException error) { throw ApiError.bad("Request must contain valid JSON."); }
    }
    public static JsonNode parse(String value) { return parse(value.getBytes(StandardCharsets.UTF_8)); }
    public static byte[] bytes(Object value) { return MAPPER.writeValueAsBytes(value); }
    public static String stringify(Object value) { return MAPPER.writeValueAsString(value); }
    public static boolean exact(JsonNode node, String... keys) {
        if (node == null || !node.isObject() || node.size() != keys.length) return false;
        return Arrays.stream(keys).allMatch(node::has);
    }
    public static String text(JsonNode node, String key) {
        return node != null && node.path(key).isTextual() ? node.path(key).asString() : "";
    }
    public static String string(JsonNode node) { return node != null && node.isTextual() ? node.asString() : ""; }
    public static int integer(JsonNode node, String key, int fallback) {
        JsonNode value = node == null ? null : node.get(key);
        return value != null && value.isIntegralNumber() && value.canConvertToInt() ? value.intValue() : fallback;
    }
    public static boolean bounded(JsonNode node, int max, boolean required) {
        return node != null && node.isTextual() && bounded(node.asString(), max, required);
    }
    public static boolean bounded(String value, int max, boolean required) {
        if (value == null || value.length() > max || (required && trim(value).isEmpty())) return false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < 32 && character != '\t' && character != '\r' && character != '\n') return false;
            if (Character.isHighSurrogate(character)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) return false;
            } else if (Character.isLowSurrogate(character)) return false;
        }
        return true;
    }
    public static boolean uuid(String value) { return value != null && value.matches("(?i)[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"); }
    public static String trim(String value) {
        int start = 0, end = value.length();
        while (start < end && jsSpace(value.charAt(start))) start++;
        while (end > start && jsSpace(value.charAt(end - 1))) end--;
        return value.substring(start, end);
    }
    private static boolean jsSpace(char value) {
        return (value >= 9 && value <= 13) || value == 32 || value == 160 || value == 0x1680 ||
            (value >= 0x2000 && value <= 0x200a) || value == 0x2028 || value == 0x2029 ||
            value == 0x202f || value == 0x205f || value == 0x3000 || value == 0xfeff;
    }
    public static String[] lines(String source) { return source.split("\\r\\n?|\\n", -1); }
    public static Set<String> strings(JsonNode values) {
        Set<String> result = new HashSet<>();
        if (values != null && values.isArray()) for (JsonNode value : values) result.add(string(value));
        return result;
    }
    public static ObjectNode fields(JsonNode source, String... names) {
        ObjectNode result = obj();
        for (String name : names) result.set(name, source.path(name));
        return result;
    }
}
