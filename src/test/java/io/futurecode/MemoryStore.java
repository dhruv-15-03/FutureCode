package io.futurecode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.time.format.DateTimeFormatterBuilder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

// Test-classpath only; production preview never substitutes memory for MySQL.
final class MemoryStore implements Store {
    final Map<String, User> users = new HashMap<>();
    final Map<String, ObjectNode> problems = new HashMap<>();
    final Map<String, ObjectNode> attempts = new HashMap<>();
    final Map<String, SessionRow> sessions = new HashMap<>();
    final Map<String, ObjectNode> bookmarks = new HashMap<>();
    final Map<String, ObjectNode> discussions = new HashMap<>();
    final Map<String, ObjectNode> votes = new HashMap<>();
    final java.util.Set<String> leaderboardUsers = new java.util.HashSet<>();
    final java.util.List<ObjectNode> audit = new java.util.ArrayList<>();
    final Map<String, ObjectNode> runs = new HashMap<>();
    final Map<String, ObjectNode> payments = new HashMap<>();
    final Map<String, String> paymentEvents = new HashMap<>();
    private final Map<String, String> subjects = new HashMap<>();
    private record SessionRow(String userId, String csrfHash, Instant expiresAt, Instant authenticatedAt) {}
    private final AppSettings settings;
    MemoryStore(AppSettings settings) { this.settings = settings; }
    private static String time() { return new DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(Instant.now()); }
    private ObjectNode load(String id) {
        ObjectNode result = problems.get(id); if (result == null) throw ApiError.missing(); return result;
    }
    private static void owner(JsonNode row, String id) { if (!Json.text(row, "authorId").equals(id)) throw ApiError.missing(); }
    private static void revision(JsonNode row, int revision) {
        if (Json.integer(row, "revision", -1) != revision) throw new ApiError(409, "The revision changed. Reload before editing.", "revision_conflict");
    }
    @Override public void ping() {}
    @Override public synchronized User upsertGoogleUser(Identity identity) {
        String existing = subjects.get(identity.sub());
        if (existing != null) {
            User old = users.get(existing);
            User replacement = new User(old.id(), identity.email(), identity.name(), old.displayName(), old.role());
            users.put(existing, replacement); return replacement;
        }
        if (users.size() >= settings.maxUsers()) throw new ApiError(503, "The beta is full.", "beta_capacity");
        String id = UUID.randomUUID().toString();
        User user = new User(id, identity.email(), identity.name(), "Test reader", "student");
        users.put(id, user); subjects.put(identity.sub(), id); return user;
    }
    @Override public synchronized void createSession(String userId, String tokenHash, String csrfHash, Instant expiresAt) {
        sessions.put(tokenHash, new SessionRow(userId, csrfHash, expiresAt, Instant.now()));
    }
    @Override public synchronized StoredSession getSession(String tokenHash) {
        SessionRow row = sessions.get(tokenHash);
        return row != null && users.containsKey(row.userId) && row.expiresAt.isAfter(Instant.now()) ? new StoredSession(users.get(row.userId), row.csrfHash, row.authenticatedAt) : null;
    }
    @Override public synchronized void removeSession(String tokenHash) { sessions.remove(tokenHash); }
    @Override public synchronized void cleanSessions() { sessions.values().removeIf(row -> row.expiresAt.isBefore(Instant.now())); }
    @Override public synchronized void updateProfile(String userId, String name) {
        User user = users.get(userId); if (user == null) throw ApiError.missing();
        users.put(userId, new User(user.id(), user.email(), user.name(), name, user.role()));
    }
    @Override public synchronized void deleteAccount(String userId) {
        users.remove(userId); subjects.values().removeIf(userId::equals);
        problems.values().removeIf(row -> Json.text(row, "authorId").equals(userId));
        attempts.values().removeIf(row -> Json.text(row, "userId").equals(userId));
        sessions.values().removeIf(row -> row.userId.equals(userId));
        bookmarks.values().removeIf(row -> Json.text(row, "userId").equals(userId));
        discussions.values().removeIf(row -> Json.text(row, "authorId").equals(userId));
        votes.values().removeIf(row -> Json.text(row, "userId").equals(userId));
        leaderboardUsers.remove(userId);
        runs.values().removeIf(row -> Json.text(row, "userId").equals(userId));
        for (ObjectNode row : payments.values()) if (Json.text(row, "userId").equals(userId)) row.putNull("userId");
        for (ObjectNode row : audit) if (Json.text(row, "actorId").equals(userId)) row.putNull("actorId");
    }
    @Override public synchronized Page listProblems(Filters filters) {
        List<ObjectNode> rows = problems.values().stream().filter(row ->
            (filters.status() == null || Json.text(row, "status").equals(filters.status())) &&
            (filters.authorId() == null || Json.text(row, "authorId").equals(filters.authorId())) &&
            (filters.search() == null || Json.text(row, "title").toLowerCase(java.util.Locale.ROOT).contains(filters.search().toLowerCase(java.util.Locale.ROOT))) &&
            (filters.difficulty() == null || filters.difficulty().isEmpty() || Json.text(row, "difficulty").equals(filters.difficulty())) &&
            (filters.language() == null || filters.language().isEmpty() || Json.text(row, "language").equalsIgnoreCase(filters.language())) &&
            (filters.cursor() == null || Instant.parse(Json.text(row, "createdAt")).isBefore(filters.cursor().at()) ||
                (Instant.parse(Json.text(row, "createdAt")).equals(filters.cursor().at()) && Json.text(row, "id").compareTo(filters.cursor().id()) < 0)))
            .sorted((first, second) -> {
                int date = Json.text(second, "createdAt").compareTo(Json.text(first, "createdAt"));
                return date == 0 ? Json.text(second, "id").compareTo(Json.text(first, "id")) : date;
            }).toList();
        ArrayNode items = Json.arr();
        rows.stream().limit(20).forEach(row -> {
            ObjectNode copy = getProblem(Json.text(row, "id")); copy.remove("body"); items.add(copy);
        });
        return new Page(items, rows.size() > 20);
    }
    @Override public synchronized ObjectNode getProblem(String id) {
        ObjectNode row = load(id).deepCopy();
        User author = users.get(Json.text(row, "authorId"));
        row.put("author", author == null ? "Deleted account" : author.displayName());
        return row;
    }
    @Override public synchronized ObjectNode saveProblem(String userId, JsonNode body, String id, Integer revision) {
        ObjectNode row;
        if (id != null) {
            row = load(id); owner(row, userId); revision(row, revision);
            if (!Set.of("draft", "rejected").contains(Json.text(row, "status"))) throw new ApiError(409, "Withdraw before editing; published problems are immutable.", "problem_locked");
            row.put("revision", Json.integer(row, "revision", 0) + 1);
        } else {
            if (problems.size() >= settings.maxProblems() || problems.values().stream().filter(item -> Json.text(item, "authorId").equals(userId)).count() >= settings.maxProblemsPerUser()) throw new ApiError(409, "Contribution capacity reached.", "problem_capacity");
            id = UUID.randomUUID().toString();
            row = Json.obj("id", id, "authorId", userId, "revision", 1, "createdAt", time(), "publishedAt", null);
            problems.put(id, row);
        }
        for (String name : List.of("title", "language", "kind", "difficulty")) row.set(name, body.path(name));
        row.set("body", body.deepCopy()); row.put("status", "draft"); row.put("reviewNote", ""); row.put("updatedAt", time());
        return getProblem(id);
    }
    @Override public synchronized ObjectNode submitProblem(String userId, String id, int revision, Consumer<JsonNode> validate) {
        ObjectNode row = load(id); owner(row, userId); revision(row, revision);
        if (!Set.of("draft", "rejected").contains(Json.text(row, "status"))) throw new ApiError(409, "Only a draft can be submitted.", "problem_locked");
        validate.accept(row.path("body")); row.put("status", "pending"); row.put("revision", revision + 1); row.put("reviewNote", ""); return getProblem(id);
    }
    @Override public synchronized ObjectNode withdrawProblem(String userId, String id, int revision) {
        ObjectNode row = load(id); owner(row, userId); revision(row, revision);
        if (!Json.text(row, "status").equals("pending")) throw new ApiError(409, "Only a pending problem can be withdrawn.", "problem_locked");
        row.put("status", "draft"); row.put("revision", revision + 1); return getProblem(id);
    }
    @Override public synchronized ObjectNode reviewProblem(String admin, String id, int revision, String decision, String note, Consumer<JsonNode> validate) {
        ObjectNode row = load(id); revision(row, revision);
        if (!Json.text(row, "status").equals("pending")) throw new ApiError(409, "The review changed.", "problem_locked");
        validate.accept(row.path("body")); row.put("status", decision.equals("publish") ? "published" : "rejected");
        row.put("reviewNote", note); row.put("revision", revision + 1); row.set("publishedAt", Json.node(decision.equals("publish") ? time() : null));
        addAudit(admin, decision.equals("publish") ? "problem.published" : "problem.rejected", id, Json.obj("previousRevision", revision, "revision", revision + 1));
        return getProblem(id);
    }
    @Override public synchronized void removeProblem(User actor, String id, int revision) {
        ObjectNode row = load(id); if (!actor.role().equals("admin")) owner(row, actor.id()); revision(row, revision);
        if (!actor.role().equals("admin") && Json.text(row, "status").equals("published")) throw new ApiError(403, "Moderator required.", "moderator_required");
        if (actor.role().equals("admin")) addAudit(actor.id(), "problem.removed", id, Json.obj("revision", revision, "previousStatus", row.path("status")));
        problems.remove(id);
    }
    @Override public synchronized ObjectNode saveAttempt(String userId, JsonNode value) {
        if (attempts.values().stream().filter(row -> Json.text(row, "userId").equals(userId)).count() >= settings.maxSavedAttempts()) throw new ApiError(409, "Saved-attempt capacity reached. Delete an older record.", "attempt_capacity");
        String id = UUID.randomUUID().toString();
        ObjectNode row = Json.fields(value, "problemKey", "title", "version", "category", "score", "maxScore", "record");
        row.put("id", id); row.put("userId", userId); row.put("createdAt", time()); attempts.put(id, row.deepCopy());
        return getAttempt(userId, id);
    }
    @Override public synchronized ArrayNode listAttempts(String userId) {
        ArrayNode result = Json.arr();
        attempts.values().stream().filter(row -> Json.text(row, "userId").equals(userId))
            .sorted((first, second) -> Json.text(second, "createdAt").compareTo(Json.text(first, "createdAt"))).forEach(row -> {
                ObjectNode copy = row.deepCopy(); copy.remove("record"); copy.remove("userId"); result.add(copy);
            });
        return result;
    }
    @Override public synchronized ObjectNode getAttempt(String userId, String id) {
        ObjectNode row = attempts.get(id);
        if (row == null || !Json.text(row, "userId").equals(userId)) throw ApiError.missing();
        ObjectNode copy = row.deepCopy(); copy.remove("userId"); return copy;
    }
    @Override public synchronized void removeAttempt(String userId, String id) { getAttempt(userId, id); attempts.remove(id); }
    @Override public synchronized boolean hasAttempt(String userId, String key) {
        return attempts.values().stream().anyMatch(row -> Json.text(row, "userId").equals(userId) && Json.text(row, "problemKey").equals(key));
    }
    @Override public synchronized void grantAdmin(String userId) {
        User user = users.get(userId); if (user == null) throw ApiError.missing();
        users.put(userId, new User(user.id(), user.email(), user.name(), user.displayName(), "admin"));
        addAudit(null, "role.granted.by-host", userId, Json.obj("role", "admin"));
    }
    private void addAudit(String actor, String action, String target, JsonNode details) {
        audit.add(Json.obj("id", UUID.randomUUID().toString(), "actorId", actor, "action", action, "targetKey", target, "details", details, "createdAt", time()));
    }
    @Override public synchronized ArrayNode listBookmarks(String userId) {
        ArrayNode result = Json.arr();
        bookmarks.values().stream().filter(row -> Json.text(row, "userId").equals(userId)).sorted((a, b) -> Json.text(b, "createdAt").compareTo(Json.text(a, "createdAt")))
            .forEach(row -> result.add(Json.fields(row, "problemKey", "title", "createdAt")));
        return result;
    }
    @Override public synchronized boolean hasBookmark(String userId, String key) { return bookmarks.containsKey(userId + "#" + key); }
    @Override public synchronized void setBookmark(String userId, String key, String title, boolean saved) {
        String id = userId + "#" + key;
        if (!saved) bookmarks.remove(id);
        else if (!bookmarks.containsKey(id)) {
            if (listBookmarks(userId).size() >= 50) throw new ApiError(409, "The free beta permits 50 saved entries.", "bookmark_capacity");
            bookmarks.put(id, Json.obj("userId", userId, "problemKey", key, "title", title, "createdAt", time()));
        }
    }
    @Override public synchronized ObjectNode votes(String key, String userId) {
        int up = 0, down = 0, mine = 0;
        for (ObjectNode row : votes.values()) if (Json.text(row, "problemKey").equals(key)) {
            int value = row.path("value").intValue();
            if (value > 0) up++; else down++;
            if (Json.text(row, "userId").equals(userId)) mine = value;
        }
        return Json.obj("score", up - down, "up", up, "down", down, "mine", mine);
    }
    @Override public synchronized void vote(String userId, String key, int value) {
        if (value < -1 || value > 1) throw ApiError.bad("Use an upvote, downvote, or clear vote.");
        String id = userId + "#" + key;
        if (value == 0) votes.remove(id); else votes.put(id, Json.obj("userId", userId, "problemKey", key, "value", value));
    }
    private ObjectNode discussion(String id) {
        ObjectNode row = discussions.get(id); if (row == null) throw ApiError.missing(); return row;
    }
    @Override public synchronized ObjectNode getDiscussion(String id) {
        ObjectNode row = discussion(id).deepCopy();
        User user = users.get(Json.text(row, "authorId")); row.put("author", user == null ? "Deleted account" : user.displayName()); return row;
    }
    @Override public synchronized Page listDiscussions(String key, Cursor cursor, boolean moderator) {
        var rows = discussions.values().stream().filter(row -> Json.text(row, "problemKey").equals(key) && (moderator || !row.path("hidden").booleanValue()) &&
            (cursor == null || Instant.parse(Json.text(row, "createdAt")).isBefore(cursor.at()) ||
                (Instant.parse(Json.text(row, "createdAt")).equals(cursor.at()) && Json.text(row, "id").compareTo(cursor.id()) < 0)))
            .sorted((a, b) -> {
                int time = Json.text(b, "createdAt").compareTo(Json.text(a, "createdAt"));
                return time == 0 ? Json.text(b, "id").compareTo(Json.text(a, "id")) : time;
            }).toList();
        ArrayNode items = Json.arr(); rows.stream().limit(20).forEach(row -> items.add(getDiscussion(Json.text(row, "id")))); return new Page(items, rows.size() > 20);
    }
    @Override public synchronized ObjectNode saveDiscussion(String userId, String key, String text, String id, Integer revision) {
        if (id != null) {
            ObjectNode row = discussion(id); if (!Json.text(row, "authorId").equals(userId)) throw ApiError.missing(); revision(row, revision);
            row.put("text", text); row.put("revision", revision + 1); row.put("updatedAt", time()); return getDiscussion(id);
        }
        if (discussions.size() >= 5000 || discussions.values().stream().filter(row -> Json.text(row, "authorId").equals(userId)).count() >= 50 ||
            discussions.values().stream().filter(row -> Json.text(row, "problemKey").equals(key)).count() >= 100) throw new ApiError(409, "The beta discussion limit was reached.", "discussion_capacity");
        String created = UUID.randomUUID().toString();
        discussions.put(created, Json.obj("id", created, "authorId", userId, "problemKey", key, "text", text, "revision", 1, "hidden", false,
            "moderationNote", "", "createdAt", time(), "updatedAt", time()));
        return getDiscussion(created);
    }
    @Override public synchronized void removeDiscussion(User actor, String id, int revision) {
        ObjectNode row = discussion(id); if (!actor.role().equals("admin") && !Json.text(row, "authorId").equals(actor.id())) throw ApiError.missing();
        revision(row, revision);
        if (actor.role().equals("admin")) addAudit(actor.id(), "discussion.removed", id, Json.obj("revision", revision, "problemKey", row.path("problemKey")));
        discussions.remove(id);
    }
    @Override public synchronized ObjectNode moderateDiscussion(String admin, String id, int revision, boolean hidden, String note) {
        ObjectNode row = discussion(id); revision(row, revision); row.put("hidden", hidden); row.put("moderationNote", note); row.put("revision", revision + 1);
        addAudit(admin, hidden ? "discussion.hidden" : "discussion.restored", id, Json.obj("previousRevision", revision, "revision", revision + 1, "problemKey", row.path("problemKey")));
        return getDiscussion(id);
    }
    @Override public synchronized Page listAudit(Cursor cursor) {
        var rows = audit.stream().filter(row -> cursor == null || Instant.parse(Json.text(row, "createdAt")).isBefore(cursor.at()) ||
            (Instant.parse(Json.text(row, "createdAt")).equals(cursor.at()) && Json.text(row, "id").compareTo(cursor.id()) < 0))
            .sorted((a, b) -> {
                int at = Json.text(b, "createdAt").compareTo(Json.text(a, "createdAt"));
                return at == 0 ? Json.text(b, "id").compareTo(Json.text(a, "id")) : at;
            }).toList();
        ArrayNode items = Json.arr(); rows.stream().limit(20).forEach(row -> items.add(row.deepCopy())); return new Page(items, rows.size() > 20);
    }
    @Override public synchronized boolean leaderboardOptIn(String userId) { return leaderboardUsers.contains(userId); }
    @Override public synchronized void setLeaderboardOptIn(String userId, boolean enabled) {
        if (enabled) leaderboardUsers.add(userId); else leaderboardUsers.remove(userId);
    }
    @Override public synchronized ArrayNode leaderboard() {
        var rows = new java.util.ArrayList<ObjectNode>();
        for (String id : leaderboardUsers) {
            User user = users.get(id); if (user == null) continue;
            Map<String, Integer> best = new HashMap<>();
            for (JsonNode attempt : attempts.values()) if (Json.text(attempt, "userId").equals(id) && Json.text(attempt, "category").equals("curated")) {
                best.merge(Json.text(attempt, "problemKey"), attempt.path("score").intValue(), Math::max);
            }
            if (!best.isEmpty()) rows.add(Json.obj("displayName", user.displayName(), "curatedChecks", best.values().stream().mapToInt(Integer::intValue).sum(), "entries", best.size(), "sortId", id));
        }
        rows.sort((a, b) -> {
            int checks = Integer.compare(b.path("curatedChecks").intValue(), a.path("curatedChecks").intValue());
            if (checks != 0) return checks;
            int entries = Integer.compare(b.path("entries").intValue(), a.path("entries").intValue());
            return entries == 0 ? Json.text(a, "sortId").compareTo(Json.text(b, "sortId")) : entries;
        });
        ArrayNode items = Json.arr();
        rows.stream().limit(20).forEach(row -> { row.remove("sortId"); row.put("rank", items.size() + 1); items.add(row); }); return items;
    }
    @Override public synchronized ObjectNode reserveRun(String userId, String key, String kind, String language, String hash) {
        if (listRuns(userId).size() >= 20) throw new ApiError(409, "Run capacity reached.", "run_capacity");
        if (runs.values().stream().anyMatch(row -> Json.text(row, "userId").equals(userId) && Set.of("submitting", "queued", "running", "unconfirmed").contains(Json.text(row, "state")))) throw new ApiError(409, "Run already pending.", "run_busy");
        String id = UUID.randomUUID().toString();
        runs.put(id, Json.obj("id", id, "userId", userId, "problemKey", key, "kind", kind, "language", language, "state", "submitting",
            "sourceHash", hash, "providerToken", null, "result", null, "createdAt", time(), "updatedAt", time()));
        return getRun(userId, id);
    }
    @Override public synchronized ObjectNode getRun(String userId, String id) {
        ObjectNode row = runs.get(id); if (row == null || !Json.text(row, "userId").equals(userId)) throw ApiError.missing();
        ObjectNode copy = row.deepCopy(); copy.remove("userId"); return copy;
    }
    @Override public synchronized void updateRun(String userId, String id, String state, String token, JsonNode result) {
        getRun(userId, id); ObjectNode row = runs.get(id);
        if (Set.of("completed", "failed").contains(Json.text(row, "state"))) return;
        row.put("state", state); if (token != null) row.put("providerToken", token); row.set("result", Json.node(result)); row.put("updatedAt", time());
    }
    @Override public synchronized ArrayNode listRuns(String userId) {
        ArrayNode items = Json.arr();
        runs.values().stream().filter(row -> Json.text(row, "userId").equals(userId)).sorted((a,b) -> Json.text(b,"createdAt").compareTo(Json.text(a,"createdAt"))).forEach(row -> {
            ObjectNode copy = getRun(userId, Json.text(row, "id")); copy.remove("providerToken"); items.add(copy);
        });
        return items;
    }
    @Override public synchronized void removeRun(String userId, String id) { getRun(userId, id); runs.remove(id); }
    @Override public synchronized ObjectNode reservePayment(String userId, String requestId, int amount) {
        for (ObjectNode row : payments.values()) if (Json.text(row, "userId").equals(userId) && Json.text(row, "requestId").equals(requestId)) return row.deepCopy().put("newlyCreated", false);
        if (listPayments(userId).size() >= 5) throw new ApiError(409, "Payment link capacity reached.", "payment_capacity");
        String id = UUID.randomUUID().toString();
        ObjectNode row = Json.obj("id", id, "userId", userId, "requestId", requestId, "providerId", null, "amountPaise", amount, "currency", "INR", "state", "creating",
            "checkoutUrl", null, "expiresAt", new DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(Instant.now().plusSeconds(3600)), "createdAt", time(), "updatedAt", time());
        payments.put(id, row); ObjectNode copy = row.deepCopy(); copy.remove("userId"); copy.remove("requestId"); return copy.put("newlyCreated", true);
    }
    @Override public synchronized void updatePayment(String userId, String id, String state, String providerId, String url) {
        ObjectNode row = payments.get(id); if (row == null || !Json.text(row, "userId").equals(userId)) throw ApiError.missing();
        if (!Set.of("creating", "created", "unconfirmed").contains(Json.text(row, "state"))) return;
        row.put("state", state); if (providerId != null) row.put("providerId", providerId); if (url != null) row.put("checkoutUrl", url);
    }
    @Override public synchronized ArrayNode listPayments(String userId) {
        ArrayNode items = Json.arr();
        payments.values().stream().filter(row -> Json.text(row, "userId").equals(userId)).sorted((a,b) -> Json.text(b,"createdAt").compareTo(Json.text(a,"createdAt"))).forEach(row -> {
            ObjectNode copy = row.deepCopy(); copy.remove("userId"); copy.remove("requestId"); copy.remove("providerId"); items.add(copy);
        }); return items;
    }
    @Override public synchronized boolean applyPaymentEvent(String eventId, String type, String reference, String providerId, int amount, String currency, String state) {
        ObjectNode row = payments.get(reference); if (row == null) throw ApiError.bad("Unknown payment reference.");
        if (row.path("amountPaise").intValue() != amount || !Json.text(row, "currency").equals(currency) ||
            (!row.path("providerId").isNull() && !Json.text(row,"providerId").equals(providerId))) throw ApiError.bad("Payment details do not match.");
        if (paymentEvents.containsKey(eventId)) {
            if (!paymentEvents.get(eventId).equals(reference)) throw ApiError.bad("Payment event identifier collision.");
            return false;
        }
        paymentEvents.put(eventId, reference);
        if (!Json.text(row, "state").equals("paid") || state.equals("paid")) { row.put("state", state); row.put("providerId", providerId); }
        return true;
    }
}
