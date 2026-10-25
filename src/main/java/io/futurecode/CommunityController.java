package io.futurecode;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;

@RestController
public final class CommunityController {
    private final Store store;
    private final PlatformController platform;
    private final Authentication auth;
    private final ProblemDirectory directory;
    private final BoundedLimiter limiter;
    public CommunityController(ObjectProvider<Store> store, PlatformController platform, Authentication auth, ProblemDirectory directory, BoundedLimiter limiter) {
        this.store = store.getIfAvailable(); this.platform = platform; this.auth = auth; this.directory = directory; this.limiter = limiter;
    }
    private void ready() {
        if (store == null) throw new ApiError(503, "Community features require the configured MySQL database.", "database_unavailable");
    }
    private static JsonNode body(HttpServletRequest request) {
        Object value = request.getAttribute(RequestBoundary.BODY);
        if (!(value instanceof JsonNode json)) throw ApiError.bad("Submit a JSON request.");
        return json;
    }
    private static int revision(JsonNode body) {
        int revision = Json.integer(body, "revision", -1);
        if (revision < 1) throw ApiError.bad("Reload the current discussion revision before editing.");
        return revision;
    }
    private static String id(String value) { if (!Json.uuid(value)) throw ApiError.missing(); return value; }
    private void attempted(Authentication.Session session, String key) {
        if (!session.user().role().equals("admin") && !store.hasAttempt(session.user().id(), key)) throw new ApiError(403, "Submit an attempt before reading spoilers, discussing, or voting on this entry.", "attempt_required");
    }
    @GetMapping("/api/community/{key}") public ObjectNode summary(@PathVariable String key, HttpServletRequest request) {
        ready(); var entry = directory.resolve(key, store); var current = auth.resolve(request);
        boolean attempted = current != null && (current.user().role().equals("admin") || store.hasAttempt(current.user().id(), key));
        boolean ownContribution = current != null && key.startsWith("community:") &&
            Json.text(store.getProblem(key.substring(10)), "authorId").equals(current.user().id());
        return Json.obj("problemKey", key, "title", entry.title(), "bookmarked", current != null && store.hasBookmark(current.user().id(), key),
            "votes", store.votes(key, current == null ? null : current.user().id()), "canDiscuss", attempted, "signedIn", current != null,
            "canVote", attempted && !ownContribution, "voteNotice", ownContribution ? "You cannot vote on your own contribution." : "");
    }
    @GetMapping("/api/bookmarks") public ObjectNode bookmarks(HttpServletRequest request) {
        var current = platform.requireAccount(request, false, false);
        ArrayNode items = store.listBookmarks(current.user().id());
        for (JsonNode item : items) {
            try { directory.resolve(Json.text(item, "problemKey"), store); ((ObjectNode) item).put("available", true); }
            catch (ApiError error) {
                if (error.status() != 404) throw error;
                ((ObjectNode) item).put("available", false).put("notice", "This entry is no longer published.");
            }
        }
        return Json.obj("items", items, "limit", 50);
    }
    @PutMapping("/api/bookmarks/{key}") public ObjectNode bookmark(@PathVariable String key, HttpServletRequest request) {
        var current = platform.requireAccount(request, true, false); var entry = directory.resolve(key, store);
        if (!Json.exact(body(request))) throw ApiError.bad("Only the entry URL is accepted.");
        store.setBookmark(current.user().id(), key, entry.title(), true); return Json.obj("saved", true);
    }
    @DeleteMapping("/api/bookmarks/{key}") public ObjectNode unbookmark(@PathVariable String key, HttpServletRequest request) {
        var current = platform.requireAccount(request, true, false);
        if (!Json.exact(body(request)) || !Json.bounded(key, 80, true)) throw ApiError.bad("Only the entry URL is accepted.");
        store.setBookmark(current.user().id(), key, "", false); return Json.obj("saved", false);
    }
    @PutMapping("/api/community/{key}/vote") public ObjectNode vote(@PathVariable String key, HttpServletRequest request) {
        var current = platform.requireAccount(request, true, false); directory.resolve(key, store); attempted(current, key);
        JsonNode input = body(request); int value = Json.integer(input, "value", -2);
        if (!Json.exact(input, "value") || value < -1 || value > 1) throw ApiError.bad("Use -1, 0, or 1 for a vote.");
        if (key.startsWith("community:") && Json.text(store.getProblem(key.substring(10)), "authorId").equals(current.user().id()) && value != 0) {
            throw new ApiError(403, "You cannot vote on your own contribution.", "self_vote");
        }
        store.vote(current.user().id(), key, value); return store.votes(key, current.user().id());
    }
    @GetMapping("/api/community/{key}/discussion") public ObjectNode discussion(@PathVariable String key, @RequestParam(required = false) String cursor, HttpServletRequest request) {
        var current = platform.requireAccount(request, false, false); directory.resolve(key, store); attempted(current, key);
        var page = store.listDiscussions(key, PlatformController.cursor(cursor), current.user().role().equals("admin"));
        ObjectNode result = PlatformController.page(page);
        ArrayNode exposed = Json.arr();
        for (JsonNode row : page.items()) {
            ObjectNode item = Json.fields(row, "id", "author", "problemKey", "text", "revision", "createdAt", "updatedAt", "hidden");
            item.put("owned", Json.text(row, "authorId").equals(current.user().id()));
            if (current.user().role().equals("admin")) item.set("moderationNote", row.path("moderationNote"));
            exposed.add(item);
        }
        result.set("items", exposed); return result;
    }
    @PostMapping("/api/community/{key}/discussion") public ObjectNode comment(@PathVariable String key, HttpServletRequest request) {
        var current = platform.requireAccount(request, true, false); directory.resolve(key, store); attempted(current, key);
        limiter.consume("discussion:" + current.user().id(), 20, 3600000);
        JsonNode input = body(request);
        if (!Json.exact(input, "text") || !Json.bounded(input.get("text"), 2000, true)) throw ApiError.bad("Write a plain-text comment of 1-2,000 characters without secrets.");
        ObjectNode row = store.saveDiscussion(current.user().id(), key, Json.trim(Json.text(input, "text")), null, null);
        return Json.fields(row, "id", "text", "revision", "createdAt");
    }
    @PutMapping("/api/discussions/{id}") public ObjectNode edit(@PathVariable String id, HttpServletRequest request) {
        var current = platform.requireAccount(request, true, false); JsonNode input = body(request);
        if (!Json.exact(input, "text", "revision") || !Json.bounded(input.get("text"), 2000, true)) throw ApiError.bad("Provide the current revision and bounded comment.");
        ObjectNode existing = store.getDiscussion(id(id));
        if (!Json.text(existing, "authorId").equals(current.user().id())) throw ApiError.missing();
        directory.resolve(Json.text(existing, "problemKey"), store);
        ObjectNode row = store.saveDiscussion(current.user().id(), Json.text(existing, "problemKey"), Json.trim(Json.text(input, "text")), id, revision(input));
        return Json.fields(row, "id", "text", "revision", "updatedAt");
    }
    @DeleteMapping("/api/discussions/{id}") public ObjectNode remove(@PathVariable String id, HttpServletRequest request) {
        var current = platform.requireAccount(request, true, false); JsonNode input = body(request);
        if (!Json.exact(input, "revision")) throw ApiError.bad("Provide only the current comment revision.");
        store.removeDiscussion(current.user(), id(id), revision(input)); return Json.obj("ok", true);
    }
    @PatchMapping("/api/discussions/{id}/moderation") public ObjectNode moderate(@PathVariable String id, HttpServletRequest request) {
        var current = platform.requireAccount(request, true, true); JsonNode input = body(request);
        if (!Json.exact(input, "revision", "hidden", "note") || !input.path("hidden").isBoolean() || !Json.bounded(input.get("note"), 300, true)) {
            throw ApiError.bad("A current revision, visibility decision, and moderator explanation are required.");
        }
        ObjectNode result = store.moderateDiscussion(current.user().id(), id(id), revision(input), input.path("hidden").booleanValue(), Json.text(input, "note"));
        return Json.fields(result, "id", "revision", "hidden", "moderationNote");
    }
    @GetMapping("/api/moderation/audit") public ObjectNode audit(@RequestParam(required = false) String cursor, HttpServletRequest request) {
        platform.requireAccount(request, false, true); return PlatformController.page(store.listAudit(PlatformController.cursor(cursor)));
    }
    @GetMapping("/api/leaderboard") public ObjectNode leaderboard() {
        ready();
        return Json.obj("items", store.leaderboard(), "notice", "Opt-in public reference-check totals from retained curated attempts. No community votes or AI judgments are included. This is not a hiring-readiness or anti-cheat ranking.");
    }
    @GetMapping("/api/leaderboard/preference") public ObjectNode preference(HttpServletRequest request) {
        var current = platform.requireAccount(request, false, false);
        return Json.obj("enabled", store.leaderboardOptIn(current.user().id()));
    }
    @PutMapping("/api/leaderboard/preference") public ObjectNode preferenceWrite(HttpServletRequest request) {
        var current = platform.requireAccount(request, true, false); JsonNode input = body(request);
        if (!Json.exact(input, "enabled") || !input.path("enabled").isBoolean()) throw ApiError.bad("Choose whether to publish your display name and curated reference totals.");
        store.setLeaderboardOptIn(current.user().id(), input.path("enabled").booleanValue()); return Json.obj("enabled", input.path("enabled"));
    }
    @GetMapping("/api/progress") public ObjectNode progress(HttpServletRequest request) {
        var current = platform.requireAccount(request, false, false);
        var topics = new LinkedHashMap<String, ObjectNode>();
        var attempted = new LinkedHashMap<String, Set<String>>();
        for (JsonNode attempt : store.listAttempts(current.user().id())) {
            String key = Json.text(attempt, "problemKey"); String topic, label;
            try { var entry = directory.resolve(key, store); topic = entry.topic(); label = entry.topicLabel(); }
            catch (ApiError error) {
                if (error.status() != 404) throw error;
                topic = "removed"; label = "Removed community entries";
            }
            ObjectNode row = topics.get(topic);
            if (row == null) {
                row = Json.obj("topic", topic, "label", label, "attempts", 0, "curatedMatches", 0,
                    "communityMatches", 0, "writtenResponses", 0, "lastPracticedAt", attempt.path("createdAt"));
                topics.put(topic, row);
            }
            row.put("attempts", row.path("attempts").intValue() + 1);
            boolean matched = !attempt.path("score").isNull() && attempt.path("score").equals(attempt.path("maxScore"));
            String field = Json.text(attempt, "category").equals("ungraded") ? "writtenResponses" : matched ? Json.text(attempt, "category").equals("curated") ? "curatedMatches" : "communityMatches" : null;
            if (field != null) row.put(field, row.path(field).intValue() + 1);
            attempted.computeIfAbsent(topic, ignored -> new HashSet<>()).add(key);
        }
        ArrayNode items = Json.arr();
        for (var entry : topics.entrySet()) { entry.getValue().put("attemptedEntries", attempted.get(entry.getKey()).size()); items.add(entry.getValue()); }
        return Json.obj("topics", items, "notice", "Private progress over retained saved attempts. Community topics describe their reading task, not independently verified subject coverage. Deleting attempts changes these totals.");
    }
}
