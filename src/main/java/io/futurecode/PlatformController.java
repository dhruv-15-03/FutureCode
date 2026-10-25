package io.futurecode;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@RestController
public final class PlatformController {
    private static final DateTimeFormatter ISO = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private record Cached(long until, ObjectNode value) {}
    private final AppSettings settings;
    private final Store store;
    private final Authentication auth;
    private final GradingService grading;
    private final CuratedContent content;
    private final BoundedLimiter limiter;
    private final Map<String, Cached> catalogCache = new LinkedHashMap<>();
    public PlatformController(AppSettings settings, ObjectProvider<Store> store, Authentication auth, GradingService grading, CuratedContent content, BoundedLimiter limiter) {
        this.settings = settings; this.store = store.getIfAvailable(); this.auth = auth; this.grading = grading; this.content = content; this.limiter = limiter;
    }
    private void ensureStore() {
        if (store == null) throw new ApiError(503, "MySQL is not connected yet. Browsing the curated problemset is available; saving and community publishing require database setup.", "database_unavailable");
    }
    public Authentication.Session requireAccount(HttpServletRequest request, boolean write, boolean admin) {
        ensureStore();
        Authentication.Session session = auth.resolve(request);
        if (session == null) throw new ApiError(401, "Sign in with Google before submitting or tracking results.", "authentication_required");
        if (write) auth.requireCsrf(session, request.getHeader("X-CSRF-Token"));
        if (admin && !session.user().role().equals("admin")) throw new ApiError(403, "Moderator access is required.", "moderator_required");
        if (write) limiter.consume("write:" + session.user().id(), 120, 60000);
        return session;
    }
    public Authentication.Session providerAccount(HttpServletRequest request) {
        var session = requireAccount(request, true, false);
        limiter.consume("provider:" + session.user().id(), 20, 600000);
        return session;
    }
    private static JsonNode body(HttpServletRequest request) {
        Object value = request.getAttribute(RequestBoundary.BODY);
        if (!(value instanceof JsonNode json)) throw ApiError.bad("Request must contain valid JSON.");
        return json;
    }
    private static String id(String id) { if (!Json.uuid(id)) throw ApiError.missing(); return id; }
    private static int revision(JsonNode input) {
        if (!Json.exact(input, "revision") || Json.integer(input, "revision", -1) < 1) throw ApiError.bad("A current contribution revision is required.");
        return Json.integer(input, "revision", -1);
    }
    static Store.Cursor cursor(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            if (value.length() > 256 || !value.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
            JsonNode parsed = Json.parse(Crypto.decode(value));
            Instant time = Instant.parse(Json.text(parsed, "at"));
            if (!Json.exact(parsed, "at", "id") || !Json.uuid(Json.text(parsed, "id")) || !ISO.format(time).equals(Json.text(parsed, "at"))) throw new IllegalArgumentException();
            return new Store.Cursor(time, Json.text(parsed, "id"));
        } catch (IllegalArgumentException | java.time.DateTimeException | ApiError error) {
            throw ApiError.bad("The page cursor is invalid. Refresh the list.");
        }
    }
    static ObjectNode page(Store.Page page) {
        JsonNode last = page.items().isEmpty() ? null : page.items().get(page.items().size() - 1);
        String next = page.more() && last != null ? Crypto.encode(Json.bytes(Json.obj("at", last.path("createdAt"), "id", last.path("id")))) : null;
        return Json.obj("items", page.items(), "nextCursor", next);
    }
    private static ObjectNode metadata(JsonNode problem) {
        return Json.fields(problem, "id", "title", "author", "language", "difficulty", "kind", "revision", "createdAt", "publishedAt");
    }
    private void clearCache() { synchronized (catalogCache) { catalogCache.clear(); } }

    @GetMapping("/api/session") public ObjectNode session(HttpServletRequest request) {
        var session = auth.resolve(request);
        return Json.obj("user", session == null ? null : session.user(), "csrfToken", session == null ? null : session.csrfToken(),
            "features", Json.obj("database", store != null, "googleSignIn", auth.available(), "freeBeta", true), "origin", settings.origin(),
            "limits", Json.obj("savedAttempts", settings.maxSavedAttempts(), "contributions", settings.maxProblemsPerUser()),
            "notice", store == null ? "Preview edition: connect MySQL and Google OAuth to enable accounts, submissions, and score tracking." :
                !auth.available() ? "Google sign-in needs its OAuth configuration before submissions can be enabled." : null);
    }
    @GetMapping("/api/catalog") public ObjectNode catalog(@RequestParam MultiValueMap<String, String> query) {
        if (!Set.of("search", "difficulty", "language", "cursor").containsAll(query.keySet())) throw ApiError.bad("The problem list contains an unsupported filter.");
        String search = query.getFirst("search") == null ? "" : query.getFirst("search");
        String language = query.getFirst("language") == null ? "" : query.getFirst("language");
        String difficulty = query.getFirst("difficulty") == null ? "" : query.getFirst("difficulty");
        if (!Json.bounded(search, 100, false) || !Json.bounded(language, 32, false) || (!difficulty.isEmpty() && !ProblemContract.DIFFICULTIES.contains(difficulty))) throw ApiError.bad("Use a search up to 100 characters and valid problem filters.");
        Store.Cursor cursor = cursor(query.getFirst("cursor"));
        ArrayNode matching = Json.arr();
        for (JsonNode item : content.catalog()) {
            if ((search.isEmpty() || (Json.text(item, "title") + " " + Json.text(item, "description")).toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT))) &&
                (language.isEmpty() || Json.text(item, "language").equalsIgnoreCase(language)) && (difficulty.isEmpty() || Json.text(item, "difficulty").equals(difficulty))) matching.add(item);
        }
        if (store == null) return Json.obj("builtins", matching, "community", Json.arr(), "nextCursor", null, "communityAvailable", false,
            "notice", "The curated collection is available. Community problems will appear after MySQL is connected and submissions are reviewed.");
        ObjectNode community;
        String cacheKey = search + "\u0000" + language + "\u0000" + difficulty + "\u0000" + query.getFirst("cursor");
        synchronized (catalogCache) {
            Cached cached = catalogCache.get(cacheKey);
            if (cached != null && cached.until > System.currentTimeMillis()) community = cached.value;
            else {
                community = page(store.listProblems(new Store.Filters("published", null, cursor, search, difficulty, language)));
                ArrayNode publicItems = Json.arr(); community.path("items").forEach(item -> publicItems.add(metadata(item))); community.set("items", publicItems);
                if (catalogCache.size() >= 32) catalogCache.remove(catalogCache.keySet().iterator().next());
                catalogCache.put(cacheKey, new Cached(System.currentTimeMillis() + 30000, community));
            }
        }
        return Json.obj("builtins", cursor == null ? matching : Json.arr(), "community", community.path("items"), "nextCursor", community.path("nextCursor"), "communityAvailable", true, "notice", null);
    }
    @PostMapping("/api/auth/google") public ObjectNode google(HttpServletRequest request, HttpServletResponse response) {
        limiter.consume("login:" + request.getRemoteAddr(), 500, 900000);
        JsonNode input = body(request);
        if (!Json.exact(input, "returnTo") || !Json.bounded(input.get("returnTo"), 80, true)) throw ApiError.bad("Choose a FutureCode page to return to.");
        var started = auth.begin(Json.text(input, "returnTo"));
        response.addHeader("Set-Cookie", started.cookie());
        return Json.obj("url", started.url());
    }
    @PostMapping("/api/auth/logout") public ObjectNode logout(HttpServletRequest request, HttpServletResponse response) {
        var current = requireAccount(request, true, false);
        if (!Json.exact(body(request))) throw ApiError.bad("Logout does not accept account details.");
        auth.logout(current); response.addHeader("Set-Cookie", auth.clearSession());
        return Json.obj("ok", true);
    }
    @GetMapping("/api/attempts") public ObjectNode attempts(HttpServletRequest request) {
        var current = requireAccount(request, false, false);
        return Json.obj("items", store.listAttempts(current.user().id()));
    }
    @PostMapping("/api/attempts") @ResponseStatus(HttpStatus.CREATED) public ObjectNode saveAttempt(HttpServletRequest request) {
        var current = requireAccount(request, true, false); ObjectNode graded = grading.grade(body(request), store);
        return Json.obj("attempt", store.saveAttempt(current.user().id(), graded.path("attempt")), "feedback", graded.path("feedback"));
    }
    @GetMapping("/api/attempts/{id}") public ObjectNode attempt(@PathVariable String id, HttpServletRequest request) {
        var current = requireAccount(request, false, false); return store.getAttempt(current.user().id(), id(id));
    }
    @DeleteMapping("/api/attempts/{id}") public ObjectNode deleteAttempt(@PathVariable String id, HttpServletRequest request) {
        var current = requireAccount(request, true, false);
        if (!Json.exact(body(request))) throw ApiError.bad("Delete an attempt by its URL, not a supplied user ID.");
        store.removeAttempt(current.user().id(), id(id)); return Json.obj("ok", true);
    }
    @PatchMapping("/api/account") public ObjectNode profile(HttpServletRequest request) {
        var current = requireAccount(request, true, false); JsonNode input = body(request);
        if (!Json.exact(input, "displayName") || !Json.bounded(input.get("displayName"), 80, true) || Json.text(input, "displayName").strip().length() < 2) throw ApiError.bad("Use a public display name of 2-80 characters.");
        String name = Json.text(input, "displayName").strip(); store.updateProfile(current.user().id(), name); clearCache();
        Store.User user = current.user();
        return Json.obj("user", new Store.User(user.id(), user.email(), user.name(), name, user.role()));
    }
    @DeleteMapping("/api/account") public ObjectNode deleteAccount(HttpServletRequest request, HttpServletResponse response) {
        var current = requireAccount(request, true, false); JsonNode input = body(request);
        if (!Json.exact(input, "confirmation") || !Json.text(input, "confirmation").equals("delete-my-account")) throw ApiError.bad("Confirm account deletion with the exact phrase shown in the account page.");
        long age = current.authenticatedAt() == null ? Long.MAX_VALUE : System.currentTimeMillis() - current.authenticatedAt().toEpochMilli();
        if (age < -300000 || age > 900000) throw new ApiError(403, "Sign in again before deleting your account, then repeat the confirmation.", "reauthentication_required");
        store.deleteAccount(current.user().id()); clearCache(); response.addHeader("Set-Cookie", auth.clearSession()); return Json.obj("ok", true);
    }
    @GetMapping("/api/contributions") public ObjectNode contributions(@RequestParam(required = false) String cursor, HttpServletRequest request) {
        var current = requireAccount(request, false, false);
        return page(store.listProblems(new Store.Filters(null, current.user().id(), cursor(cursor), "", "", "")));
    }
    @PostMapping("/api/contributions") @ResponseStatus(HttpStatus.CREATED) public ObjectNode createContribution(HttpServletRequest request) {
        var current = requireAccount(request, true, false); limiter.consume("contribute:" + current.user().id(), 20, 600000); JsonNode input = body(request);
        if (!Json.exact(input, "body")) throw ApiError.bad("Submit only the problem body. Author, role, and publication status are assigned by the server.");
        ProblemContract.validate(input.path("body"), false); return store.saveProblem(current.user().id(), input.path("body"), null, null);
    }
    @GetMapping("/api/contributions/{id}") public ObjectNode contribution(@PathVariable String id, HttpServletRequest request) {
        var current = requireAccount(request, false, false); ObjectNode problem = store.getProblem(id(id));
        if (!Json.text(problem, "authorId").equals(current.user().id())) throw ApiError.missing();
        return problem;
    }
    @PutMapping("/api/contributions/{id}") public ObjectNode updateContribution(@PathVariable String id, HttpServletRequest request) {
        var current = requireAccount(request, true, false); JsonNode input = body(request);
        if (!Json.exact(input, "body", "revision") || Json.integer(input, "revision", -1) < 1) throw ApiError.bad("Reload the draft before editing.");
        ProblemContract.validate(input.path("body"), false); return store.saveProblem(current.user().id(), input.path("body"), id(id), Json.integer(input, "revision", -1));
    }
    @PostMapping("/api/contributions/{id}/submit") public ObjectNode submit(@PathVariable String id, HttpServletRequest request) {
        var current = requireAccount(request, true, false);
        ObjectNode result = store.submitProblem(current.user().id(), id(id), revision(body(request)), value -> ProblemContract.validate(value, true)); clearCache(); return result;
    }
    @PostMapping("/api/contributions/{id}/withdraw") public ObjectNode withdraw(@PathVariable String id, HttpServletRequest request) {
        var current = requireAccount(request, true, false);
        ObjectNode result = store.withdrawProblem(current.user().id(), id(id), revision(body(request))); clearCache(); return result;
    }
    @DeleteMapping("/api/contributions/{id}") public ObjectNode deleteContribution(@PathVariable String id, HttpServletRequest request) {
        var current = requireAccount(request, true, false); store.removeProblem(current.user(), id(id), revision(body(request))); clearCache(); return Json.obj("ok", true);
    }
    @GetMapping("/api/review") public ObjectNode reviewQueue(@RequestParam(required = false) String cursor, HttpServletRequest request) {
        requireAccount(request, false, true); return page(store.listProblems(new Store.Filters("pending", null, cursor(cursor), "", "", "")));
    }
    @GetMapping("/api/review/{id}") public ObjectNode review(@PathVariable String id, HttpServletRequest request) {
        requireAccount(request, false, true); return store.getProblem(id(id));
    }
    @PostMapping("/api/review/{id}") public ObjectNode decide(@PathVariable String id, HttpServletRequest request) {
        var current = requireAccount(request, true, true); JsonNode input = body(request);
        if (!Json.exact(input, "revision", "decision", "note", "confirmed") || Json.integer(input, "revision", -1) < 1 ||
            !Set.of("publish", "reject").contains(Json.text(input, "decision")) || !Json.bounded(input.get("note"), 1000, Json.text(input, "decision").equals("reject")) ||
            !input.path("confirmed").isBoolean() || !input.path("confirmed").booleanValue()) throw ApiError.bad("Confirm review of the current revision; rejection needs an explanation.");
        ObjectNode result = store.reviewProblem(current.user().id(), id(id), Json.integer(input, "revision", -1), Json.text(input, "decision"), Json.text(input, "note"), value -> ProblemContract.validate(value, true));
        clearCache(); return result;
    }
    @DeleteMapping("/api/review/{id}") public ObjectNode removePublished(@PathVariable String id, HttpServletRequest request) {
        var current = requireAccount(request, true, true); store.removeProblem(current.user(), id(id), revision(body(request))); clearCache(); return Json.obj("ok", true);
    }
    @GetMapping("/api/problems/{id}") public ObjectNode publicProblem(@PathVariable String id) {
        ensureStore(); ObjectNode problem = store.getProblem(id(id));
        if (!Json.text(problem, "status").equals("published")) throw new ApiError(404, "Published problem not found.", "not_found");
        ObjectNode result = metadata(problem); result.set("body", ProblemContract.publicBody(problem.path("body")));
        return result.put("provenance", "Community reference · moderator-reviewed, not executed");
    }
    @GetMapping("/api/problems/{id}/solution") public ObjectNode solution(@PathVariable String id, HttpServletRequest request) {
        ensureStore(); ObjectNode problem = store.getProblem(id(id));
        if (!Json.text(problem, "status").equals("published")) throw new ApiError(404, "Published problem not found.", "not_found");
        var current = requireAccount(request, false, false);
        if (!store.hasAttempt(current.user().id(), "community:" + id)) throw new ApiError(403, "Submit an attempt before revealing the author reference.", "attempt_required");
        return Json.obj("reference", problem.path("body").path("reference"), "proposedAnswer", problem.path("body").path("correctIndex"), "notice", "Community author reference: moderator-reviewed, not automatically executed or proven correct.");
    }
}
