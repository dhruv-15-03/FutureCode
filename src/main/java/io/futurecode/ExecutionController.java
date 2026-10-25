package io.futurecode;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
public final class ExecutionController {
    private static final Map<String, Integer> LANGUAGES = Map.of("java", 62, "javascript", 63, "python", 71);
    private final IntegrationSettings settings;
    private final PlatformController platform;
    private final Store store;
    private final BoundedHttp http;
    private final BoundedLimiter limiter;
    private final CuratedContent content;
    private final String harness;
    public ExecutionController(IntegrationSettings settings, PlatformController platform, ObjectProvider<Store> store,
        BoundedHttp http, BoundedLimiter limiter, CuratedContent content) throws IOException {
        this.settings = settings; this.platform = platform; this.store = store.getIfAvailable(); this.http = http; this.limiter = limiter; this.content = content;
        try (var input = new ClassPathResource("execution/Main.java.txt").getInputStream()) { harness = new String(input.readAllBytes(), StandardCharsets.UTF_8); }
    }
    private void ready() {
        if (!settings.executionEnabled() || store == null) throw new ApiError(503, "External code execution is disabled. No source was sent or executed.", "execution_unavailable");
    }
    private static JsonNode body(HttpServletRequest request) {
        Object value = request.getAttribute(RequestBoundary.BODY);
        if (!(value instanceof JsonNode json)) throw ApiError.bad("Use a bounded JSON execution request.");
        return json;
    }
    private static String key(JsonNode input) {
        if (!Json.text(input, "key").matches("[!-~]{10,256}") || !input.path("consent").isBoolean() || !input.path("consent").booleanValue()) {
            throw ApiError.bad("A RapidAPI key and explicit permission to send code to Judge0 are required.");
        }
        return Json.text(input, "key");
    }
    private static Map<String, String> headers(String key) {
        return Map.of("Content-Type", "application/json", "X-RapidAPI-Key", key, "X-RapidAPI-Host", "judge0-ce.p.rapidapi.com");
    }
    private static JsonNode decode(BoundedHttp.Response response) {
        if (response.status() < 200 || response.status() >= 300) throw runnerError();
        try { return Json.parse(response.body()); }
        catch (ApiError error) { throw runnerError(); }
    }
    private static String b64(String value) { return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    @GetMapping("/api/execution/config") public JsonNode config() {
        return Json.obj("enabled", settings.executionEnabled() && store != null, "provider", "Judge0 CE on RapidAPI",
            "languages", Json.obj("java", "OpenJDK 13; use class Main for snippets", "python", "Python 3.8", "javascript", "Node.js 12"),
            "notice", "External provider sandbox only, not this server. Provider charges/retention apply. Legacy runtime versions differ from the Java 17 case. Execution results are not added to reference scores or hiring rankings.");
    }
    @PostMapping("/api/runs") public JsonNode create(HttpServletRequest request) {
        ready(); var current = platform.requireAccount(request, true, false); JsonNode input = body(request);
        if (!Json.exact(input, "kind", "language", "source", "stdin", "expectedOutput", "key", "consent") ||
            !Set.of("snippet", "counter-regression").contains(Json.text(input, "kind")) || !LANGUAGES.containsKey(Json.text(input, "language")) ||
            !Json.bounded(input.get("source"), 12000, true) || Json.lines(Json.text(input, "source")).length > 400 ||
            !Json.bounded(input.get("stdin"), 4000, false) || !Json.bounded(input.get("expectedOutput"), 4000, false)) {
            throw ApiError.bad("Choose a supported language, bounded plain-text program, input, expected output and explicit external-execution consent.");
        }
        String credential = key(input), kind = Json.text(input, "kind"), language = Json.text(input, "language"), source = Json.text(input, "source");
        if (kind.equals("counter-regression") && (!language.equals("java") || !Json.text(input, "stdin").isEmpty() || !Json.text(input, "expectedOutput").isEmpty())) {
            throw ApiError.bad("The counter regression uses Java and server-owned input/expectations, not client-supplied tests.");
        }
        limiter.consume("run:" + current.user().id(), 6, 3600000);
        ObjectNode run = store.reserveRun(current.user().id(), kind.equals("counter-regression") ? "case:missing-requests-v1" : "private:snippet", kind, language, Crypto.sha(source));
        String id = Json.text(run, "id");
        ObjectNode payload = Json.obj("language_id", LANGUAGES.get(language), "source_code", b64(kind.equals("counter-regression") ? harness : source),
            "stdin", b64(Json.text(input, "stdin")), "cpu_time_limit", 3, "wall_time_limit", 8,
            "memory_limit", 256000, "max_processes_and_or_threads", 40, "max_file_size", 512, "enable_network", false);
        if (kind.equals("counter-regression")) {
            payload.put("additional_files", counterFiles(source)).put("expected_output", b64("PASS: FutureCode counter regression v1\n"));
        } else if (!Json.text(input, "expectedOutput").isEmpty()) payload.put("expected_output", b64(Json.text(input, "expectedOutput")));
        try {
            JsonNode result = decode(http.request(BoundedHttp.RUNNER, headers(credential), Json.bytes(payload), 12000, Duration.ofSeconds(15)));
            String token = Json.text(result, "token"); if (!Json.uuid(token)) throw runnerError();
            JsonNode metadata = Json.obj("expectedOutputProvided", kind.equals("counter-regression") || !Json.text(input, "expectedOutput").isEmpty(),
                "harnessVersion", kind.equals("counter-regression") ? Crypto.sha(harness + Json.text(content.file("RequestController.java"), "source")) : null);
            store.updateRun(current.user().id(), id, "queued", token, metadata);
            return publicRun(store.getRun(current.user().id(), id));
        } catch (BoundedHttp.Failure | ApiError error) {
            store.updateRun(current.user().id(), id, "unconfirmed", null, Json.obj("notice", "Provider acceptance was not confirmed. Already-sent code cannot be recalled. Do not automatically retry."));
            throw runnerError();
        }
    }
    private String counterFiles(String source) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output)) {
                zip.putNextEntry(new ZipEntry("RequestCounter.java")); zip.write(source.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
                zip.putNextEntry(new ZipEntry("RequestController.java")); zip.write(Json.text(content.file("RequestController.java"), "source").getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
            return Base64.getEncoder().encodeToString(output.toByteArray());
        } catch (IOException error) { throw new IllegalStateException("The repository-owned regression harness could not be packaged."); }
    }
    @PostMapping("/api/runs/{id}/poll") public JsonNode poll(@PathVariable String id, HttpServletRequest request) {
        ready(); var current = platform.requireAccount(request, true, false); JsonNode input = body(request);
        if (!Json.uuid(id) || !Json.exact(input, "key", "consent")) throw ApiError.bad("Use the owned execution record and a fresh consented provider key.");
        String credential = key(input); ObjectNode run = store.getRun(current.user().id(), id);
        if (Set.of("completed", "failed").contains(Json.text(run, "state"))) return publicRun(run);
        String token = Json.text(run, "providerToken");
        if (!Json.uuid(token)) throw new ApiError(409, "This run has no confirmed provider token. Check the provider before retrying; deleting this record does not cancel an external job.", "run_unconfirmed");
        limiter.consume("run-poll:" + current.user().id(), 30, 600000);
        URI endpoint = URI.create("https://judge0-ce.p.rapidapi.com/submissions/" + token + "?base64_encoded=true&fields=status,stdout,stderr,compile_output,time,memory");
        JsonNode response;
        try { response = decode(http.request(endpoint, headers(credential), null, 48000, Duration.ofSeconds(15))); }
        catch (BoundedHttp.Failure error) { throw runnerError(); }
        int status = Json.integer(response.path("status"), "id", -1);
        if (status < 1 || status > 14) throw runnerError();
        JsonNode time = response.path("time"), memory = response.path("memory");
        if ((!time.isMissingNode() && !time.isNull() && (!time.isTextual() || !time.asString().matches("\\d{1,4}(?:\\.\\d{1,6})?"))) ||
            (!memory.isMissingNode() && !memory.isNull() && (!memory.isIntegralNumber() || !memory.canConvertToInt() || memory.intValue() < 0))) throw runnerError();
        String state = status == 1 ? "queued" : status == 2 ? "running" : status <= 4 ? "completed" : "failed";
        boolean compared = run.path("result").path("expectedOutputProvided").asBoolean(false);
        ObjectNode result = Json.obj("providerStatus", status, "status", Json.text(response.path("status"), "description"),
            "stdout", output(response.get("stdout")), "stderr", output(response.get("stderr")), "compileOutput", output(response.get("compile_output")),
            "timeSeconds", response.path("time").isMissingNode() ? null : response.path("time"),
            "memoryKiB", response.path("memory").isMissingNode() ? null : response.path("memory"),
            "trustedHarness", Json.text(run, "kind").equals("counter-regression"), "expectedOutputProvided", compared,
            "harnessVersion", run.path("result").path("harnessVersion").isMissingNode() ? null : run.path("result").path("harnessVersion"),
            "passedExpectedOutput", compared && status >= 3 ? status == 3 : null,
            "notice", "Observed provider execution under limited tests/runtime. Not complete semantic proof, not anti-cheat, and not a curated profile score.");
        if (!Json.bounded(result.get("status"), 120, false) || Json.bytes(result).length > 24000) throw runnerError();
        store.updateRun(current.user().id(), id, state, null, result);
        return publicRun(store.getRun(current.user().id(), id));
    }
    private static String output(JsonNode value) {
        if (value == null || value.isNull()) return "";
        if (!value.isTextual() || value.asString().length() > 16000) throw runnerError();
        try {
            byte[] bytes = Base64.getDecoder().decode(value.asString());
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            if (!Json.bounded(text, 6000, false)) throw runnerError();
            return text;
        } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException error) { throw runnerError(); }
    }
    @GetMapping("/api/runs") public JsonNode history(HttpServletRequest request) {
        var current = platform.requireAccount(request, false, false); return Json.obj("items", store.listRuns(current.user().id()));
    }
    @GetMapping("/api/runs/{id}") public JsonNode record(@PathVariable String id, HttpServletRequest request) {
        var current = platform.requireAccount(request, false, false); if (!Json.uuid(id)) throw ApiError.missing();
        return publicRun(store.getRun(current.user().id(), id));
    }
    @DeleteMapping("/api/runs/{id}") public JsonNode remove(@PathVariable String id, HttpServletRequest request) {
        var current = platform.requireAccount(request, true, false); if (!Json.uuid(id) || !Json.exact(body(request))) throw ApiError.bad("Delete only the owned record identified by the URL.");
        store.removeRun(current.user().id(), id); return Json.obj("ok", true, "notice", "Local record deleted. This does not cancel provider work or remove provider-retained data.");
    }
    private static JsonNode publicRun(JsonNode row) { return Json.fields(row, "id", "problemKey", "kind", "language", "state", "sourceHash", "result", "createdAt", "updatedAt"); }
    private static ApiError runnerError() { return new ApiError(502, "The external runner did not return a confirmed bounded result. Check its key, quota and runtime; no automatic retry or correctness score was generated.", "runner_error"); }
}
