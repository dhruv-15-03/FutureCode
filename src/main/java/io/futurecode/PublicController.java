package io.futurecode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@RestController
public final class PublicController {
    private record Asset(byte[] bytes, byte[] gzip, String type, String etag, boolean document) {}
    private final Map<String, Asset> assets = new HashMap<>();
    private final AppSettings settings;
    private final Authentication auth;
    private final Store store;
    private final ApplicationAvailability availability;
    public PublicController(AppSettings settings, Authentication auth, ObjectProvider<Store> store, ApplicationAvailability availability) throws IOException {
        this.settings = settings; this.auth = auth; this.store = store.getIfAvailable(); this.availability = availability;
        Map<String, String> files = Map.ofEntries(Map.entry("/", "index.html"), Map.entry("/privacy", "privacy.html"),
            Map.entry("/app.js", "app.js"), Map.entry("/challenges.js", "challenges.js"), Map.entry("/exercise.js", "exercise.js"),
            Map.entry("/interview-case.js", "interview-case.js"), Map.entry("/interview.js", "interview.js"),
            Map.entry("/ui.js", "ui.js"), Map.entry("/community.js", "community.js"), Map.entry("/account.js", "account.js"),
            Map.entry("/platform.js", "platform.js"), Map.entry("/contribute.js", "contribute.js"), Map.entry("/community-tools.js", "community-tools.js"), Map.entry("/shell.js", "shell.js"), Map.entry("/integrations.js", "integrations.js"),
            Map.entry("/favicon.svg", "favicon.svg"), Map.entry("/styles.css", "styles.css"));
        for (var file : files.entrySet()) {
            byte[] bytes;
            try (var input = new ClassPathResource("static/" + file.getValue()).getInputStream()) { bytes = input.readAllBytes(); }
            boolean document = file.getValue().endsWith(".html");
            String type = document ? "text/html; charset=utf-8" : file.getValue().endsWith(".js") ? "text/javascript; charset=utf-8" :
                file.getValue().endsWith(".css") ? "text/css; charset=utf-8" : "image/svg+xml";
            var compressed = new java.io.ByteArrayOutputStream();
            try (var gzip = new java.util.zip.GZIPOutputStream(compressed)) { gzip.write(bytes); }
            assets.put(file.getKey(), new Asset(bytes, compressed.toByteArray(), type, "W/\"" + Crypto.sha(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).substring(0, 24) + "\"", document));
        }
    }
    @GetMapping({"/", "/privacy", "/app.js", "/challenges.js", "/exercise.js", "/interview-case.js", "/interview.js", "/ui.js",
        "/community.js", "/account.js", "/platform.js", "/contribute.js", "/community-tools.js", "/shell.js", "/integrations.js", "/favicon.svg", "/styles.css"})
    public ResponseEntity<byte[]> asset(HttpServletRequest request) {
        if (request.getQueryString() != null) throw ApiError.missing();
        Asset asset = assets.get(request.getRequestURI());
        if (asset == null) throw ApiError.missing();
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.parseMediaType(asset.type));
        if (settings.production() && !asset.document) {
            boolean gzip = gzipAccepted(request.getHeader("Accept-Encoding"));
            headers.setETag(asset.etag); headers.setCacheControl("public, max-age=0, must-revalidate"); headers.setVary(java.util.List.of("Accept-Encoding"));
            String ifNone = request.getHeader("If-None-Match");
            if (ifNone != null && Arrays.stream(ifNone.split(",")).map(String::strip).anyMatch(value -> value.equals("*") || value.equals(asset.etag))) return ResponseEntity.status(304).headers(headers).build();
            if (gzip) return ResponseEntity.ok().headers(headers).header("Content-Encoding", "gzip").body(asset.gzip);
        }
        return ResponseEntity.ok().headers(headers).body(asset.bytes);
    }
    private static boolean gzipAccepted(String header) {
        Map<String, Double> accepted = new HashMap<>();
        for (String part : (header == null ? "" : header).split(",")) {
            String[] parameters = part.strip().toLowerCase(java.util.Locale.ROOT).split(";");
            if (parameters[0].isEmpty()) continue;
            double quality = 1;
            for (int index = 1; index < parameters.length; index++) {
                String parameter = parameters[index].strip();
                if (parameter.startsWith("q=")) {
                    String value = parameter.substring(2);
                    if (!value.matches("(?:0(?:\\.\\d{0,3})?|1(?:\\.0{0,3})?)")) throw new ApiError(406, "No supported content encoding was accepted.", "encoding_unavailable");
                    quality = Double.parseDouble(value);
                }
            }
            accepted.put(parameters[0], quality);
        }
        if (accepted.getOrDefault("gzip", accepted.getOrDefault("*", 0d)) > 0) return true;
        if (accepted.getOrDefault("identity", 1d) == 0 || (!accepted.containsKey("identity") && accepted.getOrDefault("*", 1d) == 0)) {
            throw new ApiError(406, "No supported content encoding was accepted.", "encoding_unavailable");
        }
        return false;
    }
    @GetMapping("/livez") public ObjectNode live() { return Json.obj("status", "ok"); }
    @GetMapping("/payments/return") public ResponseEntity<Void> paymentReturn() {
        return ResponseEntity.status(303).header("Location", settings.origin() + "/#account?payment=return").build();
    }
    @GetMapping("/readyz") public ObjectNode ready() {
        if (availability.getReadinessState() != ReadinessState.ACCEPTING_TRAFFIC) throw new ApiError(503, "Application startup or shutdown is in progress.", "not_ready");
        if (store == null || !auth.available()) throw new ApiError(503, "MySQL and Google OAuth setup are required for submissions.", "setup_required");
        store.ping(); return Json.obj("status", "ready");
    }
    @GetMapping("/auth/google/callback") public ResponseEntity<Void> callback(@RequestParam MultiValueMap<String, String> query, HttpServletRequest request, HttpServletResponse response) {
        String destination;
        try {
            Authentication.Finish result = auth.finish(query, GoogleAuthentication.cookieHeader(request));
            destination = result.destination(); response.addHeader("Set-Cookie", result.cookie());
        } catch (ApiError error) {
            String notice = Set.of("sign_in_cancelled", "sign_in_expired", "sign_in_unavailable", "beta_capacity").contains(error.code()) ? error.code() :
                Set.of("database_unavailable", "database_busy", "schema_unavailable").contains(error.code()) ? "storage_unavailable" : "sign_in_failed";
            destination = "account?notice=" + notice;
        }
        response.addHeader("Set-Cookie", auth.clearFlow());
        return ResponseEntity.status(303).header("Location", settings.origin() + "/#" + destination).build();
    }
}
