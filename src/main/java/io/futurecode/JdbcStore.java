package io.futurecode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class JdbcStore implements Store {
    private static final Logger LOG = LoggerFactory.getLogger(JdbcStore.class);
    private static final DateTimeFormatter ISO = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private static final String PROBLEM_SELECT = "SELECT p.*, u.display_name AS author FROM fc_problems p JOIN fc_users u ON u.id=p.author_id WHERE p.id=?";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AppSettings settings;

    public JdbcStore(JdbcTemplate jdbc, TransactionTemplate transactions, AppSettings settings) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.settings = settings;
        jdbc.setQueryTimeout(8);
        transactions.setTimeout(15);
    }
    private <T> T safe(Supplier<T> work) {
        try { return work.get(); }
        catch (DataAccessException | org.springframework.transaction.TransactionException error) {
            Throwable cause = error.getMostSpecificCause();
            LOG.error("Database operation failed (SQL state {}, vendor code {}).",
                cause instanceof SQLException sql ? sql.getSQLState() : "unavailable",
                cause instanceof SQLException sql ? sql.getErrorCode() : 0);
            throw ApiError.storage();
        }
    }
    private <T> T tx(Supplier<T> work) { return safe(() -> transactions.execute(status -> work.get())); }
    private void update(String sql, Object... args) { safe(() -> jdbc.update(sql, args)); }
    private long count(String sql, Object... args) {
        Long result = jdbc.queryForObject(sql, Long.class, args);
        if (result == null) throw new IllegalStateException("Database count is missing.");
        return result;
    }
    private static String iso(ResultSet row, String column) throws SQLException {
        Timestamp value = row.getTimestamp(column, java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")));
        return value == null ? null : ISO.format(value.toInstant());
    }
    private static User user(ResultSet row) throws SQLException {
        return new User(row.getString("id"), row.getString("email"), row.getString("google_name"), row.getString("display_name"), row.getString("role"));
    }
    private static RowMapper<ObjectNode> problemMapper(boolean body) {
        return (row, index) -> {
            ObjectNode value = Json.obj("id", row.getString("id"), "authorId", row.getString("author_id"), "author", row.getString("author"),
                "title", row.getString("title"), "language", row.getString("language"), "difficulty", row.getString("difficulty"), "kind", row.getString("kind"),
                "status", row.getString("status"), "revision", row.getInt("revision"), "reviewNote", row.getString("review_note"),
                "createdAt", iso(row, "created_at"), "updatedAt", iso(row, "updated_at"), "publishedAt", iso(row, "published_at"));
            if (body) value.set("body", Json.MAPPER.readTree(row.getString("body")));
            return value;
        };
    }
    private static RowMapper<ObjectNode> attemptMapper(boolean record) {
        return (row, index) -> {
            ObjectNode value = Json.obj("id", row.getString("id"), "problemKey", row.getString("problem_key"), "title", row.getString("title"),
                "version", row.getString("problem_version"), "category", row.getString("category"), "score", row.getObject("score"),
                "maxScore", row.getObject("max_score"), "createdAt", iso(row, "created_at"));
            if (record) value.set("record", Json.MAPPER.readTree(row.getString("record")));
            return value;
        };
    }
    private ObjectNode load(String id, boolean lock) {
        List<ObjectNode> rows = jdbc.query(PROBLEM_SELECT + (lock ? " FOR UPDATE" : ""), problemMapper(true), id);
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }
    private static void owner(JsonNode problem, String userId) {
        if (!Json.text(problem, "authorId").equals(userId)) throw ApiError.missing();
    }
    private static void revision(JsonNode problem, int revision) {
        if (Json.integer(problem, "revision", -1) != revision) throw new ApiError(409, "This problem changed in another tab or review. Reload before editing.", "revision_conflict");
    }
    private void quotaLock() {
        if (jdbc.queryForList("SELECT id FROM fc_app_lock WHERE id=1 FOR UPDATE").isEmpty()) throw new ApiError(503, "The quota lock is missing. Check database migrations.", "schema_unavailable");
    }
    private void userLock(String id) {
        if (jdbc.queryForList("SELECT id FROM fc_users WHERE id=? FOR UPDATE", id).isEmpty()) throw new ApiError(401, "Sign in again.", "authentication_required");
    }
    private void audit(String actor, String action, String target, JsonNode details) {
        if (count("SELECT COUNT(*) FROM fc_moderation_audit") >= 20000) throw new ApiError(409, "The moderation audit store is at capacity. Ask the operator to archive it before further moderation.", "audit_capacity");
        jdbc.update("INSERT INTO fc_moderation_audit(id,actor_id,action,target_key,details) VALUES (?,?,?,?,?)",
            UUID.randomUUID().toString(), actor, action, target, Json.stringify(details));
    }

    @Override public void ping() { safe(() -> jdbc.queryForObject("SELECT 1", Integer.class)); }
    @Override public User upsertGoogleUser(Identity identity) {
        return tx(() -> {
            quotaLock();
            List<User> existing = jdbc.query("SELECT * FROM fc_users WHERE google_sub=? FOR UPDATE", (row, index) -> user(row), identity.sub());
            if (!existing.isEmpty()) {
                User found = existing.getFirst();
                jdbc.update("UPDATE fc_users SET email=?, google_name=? WHERE id=?", identity.email(), identity.name(), found.id());
                return new User(found.id(), identity.email(), identity.name(), found.displayName(), found.role());
            }
            if (count("SELECT COUNT(*) FROM fc_users") >= settings.maxUsers()) throw new ApiError(503, "The initial beta is at its current account capacity. Contact the beta administrator.", "beta_capacity");
            String id = UUID.randomUUID().toString(), displayName = "Reader " + id.substring(0, 8);
            jdbc.update("INSERT INTO fc_users (id,google_sub,email,google_name,display_name) VALUES (?,?,?,?,?)", id, identity.sub(), identity.email(), identity.name(), displayName);
            return new User(id, identity.email(), identity.name(), displayName, "student");
        });
    }
    @Override public void createSession(String userId, String tokenHash, String csrfHash, Instant expiresAt) {
        tx(() -> {
            if (jdbc.queryForList("SELECT id FROM fc_users WHERE id=? FOR UPDATE", userId).isEmpty()) throw new ApiError(401, "Sign in again.", "authentication_required");
            jdbc.update("DELETE FROM fc_sessions WHERE user_id=? AND expires_at<=UTC_TIMESTAMP(3)", userId);
            List<String> tokens = jdbc.queryForList("SELECT token_hash FROM fc_sessions WHERE user_id=? ORDER BY created_at DESC,token_hash DESC", String.class, userId);
            for (int index = 4; index < tokens.size(); index++) jdbc.update("DELETE FROM fc_sessions WHERE token_hash=?", tokens.get(index));
            jdbc.update("INSERT INTO fc_sessions (token_hash,user_id,csrf_hash,expires_at) VALUES (?,?,?,?)", tokenHash, userId, csrfHash, Timestamp.from(expiresAt));
            return null;
        });
    }
    @Override public StoredSession getSession(String tokenHash) {
        return safe(() -> {
            List<StoredSession> rows = jdbc.query("SELECT u.*,s.csrf_hash,s.created_at AS authenticated_at FROM fc_sessions s JOIN fc_users u ON u.id=s.user_id WHERE s.token_hash=? AND s.expires_at>UTC_TIMESTAMP(3)",
                (row, index) -> new StoredSession(user(row), row.getString("csrf_hash"), Instant.parse(iso(row, "authenticated_at"))), tokenHash);
            return rows.isEmpty() ? null : rows.getFirst();
        });
    }
    @Override public void removeSession(String tokenHash) { update("DELETE FROM fc_sessions WHERE token_hash=?", tokenHash); }
    @Override public void cleanSessions() { update("DELETE FROM fc_sessions WHERE expires_at<=UTC_TIMESTAMP(3) LIMIT 500"); }
    @Override public void updateProfile(String userId, String displayName) { update("UPDATE fc_users SET display_name=? WHERE id=?", displayName, userId); }
    @Override public void deleteAccount(String userId) { tx(() -> jdbc.update("DELETE FROM fc_users WHERE id=?", userId)); }
    @Override public Page listProblems(Filters filters) {
        return safe(() -> {
            List<String> where = new ArrayList<>();
            List<Object> args = new ArrayList<>();
            if (filters.status() != null) { where.add("p.status=?"); args.add(filters.status()); }
            if (filters.authorId() != null) { where.add("p.author_id=?"); args.add(filters.authorId()); }
            if (filters.search() != null && !filters.search().isEmpty()) {
                where.add("p.title LIKE ? ESCAPE '!'");
                args.add("%" + filters.search().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%");
            }
            if (filters.difficulty() != null && !filters.difficulty().isEmpty()) { where.add("p.difficulty=?"); args.add(filters.difficulty()); }
            if (filters.language() != null && !filters.language().isEmpty()) { where.add("p.language=?"); args.add(filters.language()); }
            if (filters.cursor() != null) {
                where.add("(p.created_at<? OR (p.created_at=? AND p.id<?))");
                args.add(Timestamp.from(filters.cursor().at())); args.add(Timestamp.from(filters.cursor().at())); args.add(filters.cursor().id());
            }
            String columns = "p.id,p.author_id,p.title,p.language,p.difficulty,p.kind,p.status,p.revision,p.review_note,p.created_at,p.updated_at,p.published_at,u.display_name AS author";
            List<ObjectNode> rows = jdbc.query("SELECT " + columns + " FROM fc_problems p JOIN fc_users u ON u.id=p.author_id" +
                (where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where)) + " ORDER BY p.created_at DESC,p.id DESC LIMIT 21", problemMapper(false), args.toArray());
            ArrayNode items = Json.arr();
            rows.stream().limit(20).forEach(items::add);
            return new Page(items, rows.size() > 20);
        });
    }
    @Override public ObjectNode getProblem(String id) { return safe(() -> load(id, false)); }
    @Override public ObjectNode saveProblem(String userId, JsonNode body, String id, Integer revision) {
        return tx(() -> {
            if (id != null) {
                ObjectNode problem = load(id, true); owner(problem, userId); revision(problem, revision);
                if (!Set.of("draft", "rejected").contains(Json.text(problem, "status"))) throw new ApiError(409, "Withdraw a pending review before editing. Published problems cannot be silently changed.", "problem_locked");
                jdbc.update("UPDATE fc_problems SET title=?,language=?,difficulty=?,kind=?,body=?,status='draft',revision=revision+1,review_note='',reviewed_by=NULL WHERE id=?",
                    Json.text(body, "title").strip(), Json.text(body, "language"), Json.text(body, "difficulty"), Json.text(body, "kind"), Json.stringify(body), id);
                return load(id, false);
            }
            quotaLock();
            if (count("SELECT COUNT(*) FROM fc_problems") >= settings.maxProblems() || count("SELECT COUNT(*) FROM fc_problems WHERE author_id=?", userId) >= settings.maxProblemsPerUser()) {
                throw new ApiError(409, "Initial-beta storage permits " + settings.maxProblemsPerUser() + " contributions per account and " + settings.maxProblems() + " overall. Remove an unused draft or contact a moderator.", "problem_capacity");
            }
            String key = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO fc_problems (id,author_id,title,language,difficulty,kind,body) VALUES (?,?,?,?,?,?,?)",
                key, userId, Json.text(body, "title").strip(), Json.text(body, "language"), Json.text(body, "difficulty"), Json.text(body, "kind"), Json.stringify(body));
            return load(key, false);
        });
    }
    @Override public ObjectNode submitProblem(String userId, String id, int revision, Consumer<JsonNode> validate) {
        return tx(() -> {
            ObjectNode problem = load(id, true); owner(problem, userId); revision(problem, revision);
            if (!Set.of("draft", "rejected").contains(Json.text(problem, "status"))) throw new ApiError(409, "Only a draft or rejected problem can be submitted for review.", "problem_locked");
            validate.accept(problem.path("body"));
            jdbc.update("UPDATE fc_problems SET status='pending',revision=revision+1,review_note='',reviewed_by=NULL WHERE id=?", id);
            return load(id, false);
        });
    }
    @Override public ObjectNode withdrawProblem(String userId, String id, int revision) {
        return tx(() -> {
            ObjectNode problem = load(id, true); owner(problem, userId); revision(problem, revision);
            if (!Json.text(problem, "status").equals("pending")) throw new ApiError(409, "Only a pending review can be withdrawn.", "problem_locked");
            jdbc.update("UPDATE fc_problems SET status='draft',revision=revision+1 WHERE id=?", id);
            return load(id, false);
        });
    }
    @Override public ObjectNode reviewProblem(String adminId, String id, int revision, String decision, String note, Consumer<JsonNode> validate) {
        return tx(() -> {
            quotaLock();
            ObjectNode problem = load(id, true); revision(problem, revision);
            if (!Json.text(problem, "status").equals("pending")) throw new ApiError(409, "This problem is no longer awaiting review.", "problem_locked");
            validate.accept(problem.path("body"));
            String status = decision.equals("publish") ? "published" : "rejected";
            jdbc.update("UPDATE fc_problems SET status=?,review_note=?,reviewed_by=?,revision=revision+1,published_at=CASE WHEN ?='published' THEN UTC_TIMESTAMP(3) ELSE NULL END WHERE id=?", status, note, adminId, status, id);
            audit(adminId, "problem." + status, id, Json.obj("previousRevision", revision, "revision", revision + 1));
            return load(id, false);
        });
    }
    @Override public void removeProblem(User actor, String id, int revision) {
        tx(() -> {
            quotaLock();
            ObjectNode problem = load(id, true);
            if (!actor.role().equals("admin")) owner(problem, actor.id());
            revision(problem, revision);
            if (Json.text(problem, "status").equals("published") && !actor.role().equals("admin")) throw new ApiError(403, "A moderator must remove a published problem.", "moderator_required");
            if (actor.role().equals("admin")) audit(actor.id(), "problem.removed", id, Json.obj("revision", revision, "previousStatus", problem.path("status")));
            jdbc.update("DELETE FROM fc_problems WHERE id=?", id);
            return null;
        });
    }
    @Override public ObjectNode saveAttempt(String userId, JsonNode attempt) {
        return tx(() -> {
            if (jdbc.queryForList("SELECT id FROM fc_users WHERE id=? FOR UPDATE", userId).isEmpty()) throw new ApiError(401, "Sign in again.", "authentication_required");
            if (count("SELECT COUNT(*) FROM fc_attempts WHERE user_id=?", userId) >= settings.maxSavedAttempts()) throw new ApiError(409, "The free beta keeps up to " + settings.maxSavedAttempts() + " saved attempts per account. Download or delete an older attempt before saving another.", "attempt_capacity");
            String id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO fc_attempts (id,user_id,problem_key,title,problem_version,category,score,max_score,`record`) VALUES (?,?,?,?,?,?,?,?,?)",
                id, userId, Json.text(attempt, "problemKey"), Json.text(attempt, "title"), Json.text(attempt, "version"), Json.text(attempt, "category"),
                attempt.path("score").isNull() ? null : attempt.path("score").intValue(), attempt.path("maxScore").isNull() ? null : attempt.path("maxScore").intValue(), Json.stringify(attempt.path("record")));
            return getAttempt(userId, id);
        });
    }
    @Override public ArrayNode listAttempts(String userId) {
        return safe(() -> {
            ArrayNode result = Json.arr();
            jdbc.query("SELECT id,problem_key,title,problem_version,category,score,max_score,created_at FROM fc_attempts WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 100", attemptMapper(false), userId).forEach(result::add);
            return result;
        });
    }
    @Override public ObjectNode getAttempt(String userId, String id) {
        return safe(() -> {
            List<ObjectNode> rows = jdbc.query("SELECT * FROM fc_attempts WHERE id=? AND user_id=?", attemptMapper(true), id, userId);
            if (rows.isEmpty()) throw new ApiError(404, "Saved attempt not found.", "not_found");
            return rows.getFirst();
        });
    }
    @Override public void removeAttempt(String userId, String id) {
        safe(() -> {
            if (jdbc.update("DELETE FROM fc_attempts WHERE id=? AND user_id=?", id, userId) == 0) throw ApiError.missing();
            return null;
        });
    }
    @Override public boolean hasAttempt(String userId, String problemKey) {
        return safe(() -> !jdbc.queryForList("SELECT id FROM fc_attempts WHERE user_id=? AND problem_key=? LIMIT 1", userId, problemKey).isEmpty());
    }
    @Override public void grantAdmin(String userId) {
        tx(() -> {
            quotaLock();
            if (jdbc.update("UPDATE fc_users SET `role`='admin' WHERE id=?", userId) == 0) throw new ApiError(404, "Account not found. Sign in once before granting the role.", "not_found");
            audit(null, "role.granted.by-host", userId, Json.obj("role", "admin"));
            return null;
        });
    }
    @Override public ArrayNode listBookmarks(String userId) {
        return safe(() -> {
            ArrayNode items = Json.arr();
            jdbc.query("SELECT problem_key,title,created_at FROM fc_bookmarks WHERE user_id=? ORDER BY created_at DESC,problem_key LIMIT 50",
                (row, index) -> Json.obj("problemKey", row.getString("problem_key"), "title", row.getString("title"), "createdAt", iso(row, "created_at")), userId).forEach(items::add);
            return items;
        });
    }
    @Override public boolean hasBookmark(String userId, String key) {
        return safe(() -> count("SELECT COUNT(*) FROM fc_bookmarks WHERE user_id=? AND problem_key=?", userId, key) > 0);
    }
    @Override public void setBookmark(String userId, String key, String title, boolean saved) {
        tx(() -> {
            userLock(userId);
            if (!saved) jdbc.update("DELETE FROM fc_bookmarks WHERE user_id=? AND problem_key=?", userId, key);
            else if (count("SELECT COUNT(*) FROM fc_bookmarks WHERE user_id=? AND problem_key=?", userId, key) == 0) {
                if (count("SELECT COUNT(*) FROM fc_bookmarks WHERE user_id=?", userId) >= 50) throw new ApiError(409, "The free beta permits 50 saved entries. Remove one before adding another.", "bookmark_capacity");
                jdbc.update("INSERT INTO fc_bookmarks(user_id,problem_key,title) VALUES (?,?,?)", userId, key, title);
            }
            return null;
        });
    }
    @Override public ObjectNode votes(String key, String userId) {
        return safe(() -> jdbc.queryForObject(
            "SELECT COALESCE(SUM(value),0) AS score,COALESCE(SUM(value=1),0) AS up,COALESCE(SUM(value=-1),0) AS down,COALESCE(SUM(CASE WHEN user_id=? THEN value ELSE 0 END),0) AS mine FROM fc_problem_votes WHERE problem_key=?",
            (row, index) -> Json.obj("score", row.getInt("score"), "up", row.getInt("up"), "down", row.getInt("down"), "mine", row.getInt("mine")), userId == null ? "" : userId, key));
    }
    @Override public void vote(String userId, String key, int value) {
        if (value < -1 || value > 1) throw ApiError.bad("Use an upvote, downvote, or clear vote.");
        tx(() -> {
            userLock(userId);
            if (value == 0) jdbc.update("DELETE FROM fc_problem_votes WHERE user_id=? AND problem_key=?", userId, key);
            else jdbc.update("INSERT INTO fc_problem_votes(user_id,problem_key,value) VALUES (?,?,?) ON DUPLICATE KEY UPDATE value=?", userId, key, value, value);
            return null;
        });
    }
    private static RowMapper<ObjectNode> discussionMapper() {
        return (row, index) -> Json.obj("id", row.getString("id"), "authorId", row.getString("user_id"), "author", row.getString("author"),
            "problemKey", row.getString("problem_key"), "text", row.getString("body"), "hidden", row.getBoolean("hidden"), "revision", row.getInt("revision"),
            "moderationNote", row.getString("moderation_note"), "createdAt", iso(row, "created_at"), "updatedAt", iso(row, "updated_at"));
    }
    private ObjectNode discussion(String id, boolean lock) {
        var rows = jdbc.query("SELECT d.*,u.display_name AS author FROM fc_discussions d JOIN fc_users u ON u.id=d.user_id WHERE d.id=?" + (lock ? " FOR UPDATE" : ""), discussionMapper(), id);
        if (rows.isEmpty()) throw ApiError.missing();
        return rows.getFirst();
    }
    @Override public Page listDiscussions(String key, Cursor cursor, boolean moderator) {
        return safe(() -> {
            var args = new ArrayList<Object>(); args.add(key);
            String where = "d.problem_key=?" + (moderator ? "" : " AND d.hidden=FALSE");
            if (cursor != null) { where += " AND (d.created_at<? OR (d.created_at=? AND d.id<?))"; args.add(Timestamp.from(cursor.at())); args.add(Timestamp.from(cursor.at())); args.add(cursor.id()); }
            var rows = jdbc.query("SELECT d.*,u.display_name AS author FROM fc_discussions d JOIN fc_users u ON u.id=d.user_id WHERE " + where + " ORDER BY d.created_at DESC,d.id DESC LIMIT 21", discussionMapper(), args.toArray());
            ArrayNode items = Json.arr(); rows.stream().limit(20).forEach(items::add); return new Page(items, rows.size() > 20);
        });
    }
    @Override public ObjectNode getDiscussion(String id) { return safe(() -> discussion(id, false)); }
    @Override public ObjectNode saveDiscussion(String userId, String key, String text, String id, Integer revision) {
        return tx(() -> {
            if (id != null) {
                var row = discussion(id, true);
                if (!Json.text(row, "authorId").equals(userId)) throw ApiError.missing();
                revision(row, revision);
                jdbc.update("UPDATE fc_discussions SET body=?,revision=revision+1 WHERE id=?", text, id);
                return discussion(id, false);
            }
            quotaLock(); userLock(userId);
            if (count("SELECT COUNT(*) FROM fc_discussions") >= 5000 || count("SELECT COUNT(*) FROM fc_discussions WHERE user_id=?", userId) >= 50 ||
                count("SELECT COUNT(*) FROM fc_discussions WHERE problem_key=?", key) >= 100) throw new ApiError(409, "The beta discussion limit was reached. Remove an unused comment or contact a moderator.", "discussion_capacity");
            String created = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO fc_discussions(id,user_id,problem_key,body) VALUES (?,?,?,?)", created, userId, key, text);
            return discussion(created, false);
        });
    }
    @Override public void removeDiscussion(User actor, String id, int revision) {
        tx(() -> {
            quotaLock();
            var row = discussion(id, true);
            if (!actor.role().equals("admin") && !Json.text(row, "authorId").equals(actor.id())) throw ApiError.missing();
            revision(row, revision);
            if (actor.role().equals("admin")) audit(actor.id(), "discussion.removed", id, Json.obj("revision", revision, "problemKey", row.path("problemKey")));
            jdbc.update("DELETE FROM fc_discussions WHERE id=?", id);
            return null;
        });
    }
    @Override public ObjectNode moderateDiscussion(String adminId, String id, int revision, boolean hidden, String note) {
        return tx(() -> {
            quotaLock();
            var row = discussion(id, true); revision(row, revision);
            jdbc.update("UPDATE fc_discussions SET hidden=?,moderation_note=?,revision=revision+1 WHERE id=?", hidden, note, id);
            audit(adminId, hidden ? "discussion.hidden" : "discussion.restored", id, Json.obj("previousRevision", revision, "revision", revision + 1, "problemKey", row.path("problemKey")));
            return discussion(id, false);
        });
    }
    @Override public Page listAudit(Cursor cursor) {
        return safe(() -> {
            Object[] args = cursor == null ? new Object[0] : new Object[]{Timestamp.from(cursor.at()), Timestamp.from(cursor.at()), cursor.id()};
            var rows = jdbc.query("SELECT * FROM fc_moderation_audit" + (cursor == null ? "" : " WHERE created_at<? OR (created_at=? AND id<?)") + " ORDER BY created_at DESC,id DESC LIMIT 21",
                (row, index) -> Json.obj("id", row.getString("id"), "actorId", row.getString("actor_id"), "action", row.getString("action"),
                    "targetKey", row.getString("target_key"), "details", Json.MAPPER.readTree(row.getString("details")), "createdAt", iso(row, "created_at")), args);
            ArrayNode items = Json.arr(); rows.stream().limit(20).forEach(items::add); return new Page(items, rows.size() > 20);
        });
    }
    @Override public boolean leaderboardOptIn(String userId) {
        return safe(() -> Boolean.TRUE.equals(jdbc.queryForObject("SELECT leaderboard_opt_in FROM fc_users WHERE id=?", Boolean.class, userId)));
    }
    @Override public void setLeaderboardOptIn(String userId, boolean enabled) { update("UPDATE fc_users SET leaderboard_opt_in=? WHERE id=?", enabled, userId); }
    @Override public ArrayNode leaderboard() {
        return safe(() -> {
            ArrayNode result = Json.arr();
            jdbc.query("SELECT u.display_name,SUM(a.best) AS reference_checks,COUNT(*) AS entries FROM fc_users u JOIN " +
                "(SELECT user_id,problem_key,MAX(score) AS best FROM fc_attempts WHERE category='curated' GROUP BY user_id,problem_key) a ON a.user_id=u.id " +
                "WHERE u.leaderboard_opt_in=TRUE GROUP BY u.id,u.display_name ORDER BY reference_checks DESC,entries DESC,u.id LIMIT 20",
                (row, index) -> Json.obj("rank", index + 1, "displayName", row.getString("display_name"), "curatedChecks", row.getInt("reference_checks"), "entries", row.getInt("entries"))).forEach(result::add);
            return result;
        });
    }
    private static RowMapper<ObjectNode> runMapper() {
        return (row, index) -> Json.obj("id", row.getString("id"), "problemKey", row.getString("problem_key"), "kind", row.getString("kind"),
            "language", row.getString("language"), "state", row.getString("state"), "sourceHash", row.getString("source_hash"),
            "providerToken", row.getString("provider_token"), "result", row.getString("result") == null ? null : Json.MAPPER.readTree(row.getString("result")),
            "createdAt", iso(row, "created_at"), "updatedAt", iso(row, "updated_at"));
    }
    @Override public ObjectNode reserveRun(String userId, String key, String kind, String language, String hash) {
        return tx(() -> {
            userLock(userId);
            if (count("SELECT COUNT(*) FROM fc_runs WHERE user_id=?", userId) >= 20) throw new ApiError(409, "Keep at most 20 private execution records. Delete an older record first.", "run_capacity");
            if (count("SELECT COUNT(*) FROM fc_runs WHERE user_id=? AND state IN ('submitting','queued','running','unconfirmed')", userId) > 0) throw new ApiError(409, "An external run is pending or unconfirmed. Resolve it before creating another.", "run_busy");
            String id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO fc_runs(id,user_id,problem_key,kind,language,state,source_hash) VALUES (?,?,?,?,?,'submitting',?)", id, userId, key, kind, language, hash);
            return getRun(userId, id);
        });
    }
    @Override public void updateRun(String userId, String id, String state, String token, JsonNode result) {
        update("UPDATE fc_runs SET state=?,provider_token=COALESCE(?,provider_token),result=? WHERE id=? AND user_id=? AND state NOT IN ('completed','failed')",
            state, token, result == null ? null : Json.stringify(result), id, userId);
    }
    @Override public ObjectNode getRun(String userId, String id) {
        return safe(() -> {
            var rows = jdbc.query("SELECT * FROM fc_runs WHERE id=? AND user_id=?", runMapper(), id, userId);
            if (rows.isEmpty()) throw ApiError.missing(); return rows.getFirst();
        });
    }
    @Override public ArrayNode listRuns(String userId) {
        return safe(() -> {
            ArrayNode items = Json.arr();
            jdbc.query("SELECT * FROM fc_runs WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 20", runMapper(), userId).forEach(row -> {
                row.remove("providerToken"); items.add(row);
            });
            return items;
        });
    }
    @Override public void removeRun(String userId, String id) {
        safe(() -> { if (jdbc.update("DELETE FROM fc_runs WHERE id=? AND user_id=?", id, userId) == 0) throw ApiError.missing(); return null; });
    }
    private static RowMapper<ObjectNode> paymentMapper() {
        return (row, index) -> Json.obj("id", row.getString("id"), "providerId", row.getString("provider_id"), "amountPaise", row.getInt("amount_paise"),
            "currency", row.getString("currency"), "state", row.getString("state"), "checkoutUrl", row.getString("checkout_url"),
            "expiresAt", iso(row, "expires_at"), "createdAt", iso(row, "created_at"), "updatedAt", iso(row, "updated_at"));
    }
    @Override public ObjectNode reservePayment(String userId, String requestId, int amount) {
        return tx(() -> {
            quotaLock();
            userLock(userId);
            var existing = jdbc.query("SELECT * FROM fc_payment_links WHERE user_id=? AND request_id=?", paymentMapper(), userId, requestId);
            if (!existing.isEmpty()) return existing.getFirst().put("newlyCreated", false);
            if (count("SELECT COUNT(*) FROM fc_payment_links") >= 10000 ||
                count("SELECT COUNT(*) FROM fc_payment_links WHERE user_id=? AND created_at>UTC_TIMESTAMP()-INTERVAL 1 DAY", userId) >= 5) {
                throw new ApiError(409, "The payment-link creation limit was reached. Use an existing link or contact the operator.", "payment_capacity");
            }
            String id = UUID.randomUUID().toString();
            jdbc.update("INSERT INTO fc_payment_links(id,user_id,request_id,amount_paise,state,expires_at) VALUES (?,?,?,?,'creating',?)",
                id, userId, requestId, amount, Timestamp.from(Instant.now().plusSeconds(3600)));
            return jdbc.query("SELECT * FROM fc_payment_links WHERE id=?", paymentMapper(), id).getFirst().put("newlyCreated", true);
        });
    }
    @Override public void updatePayment(String userId, String id, String state, String providerId, String url) {
        update("UPDATE fc_payment_links SET state=?,provider_id=COALESCE(?,provider_id),checkout_url=COALESCE(?,checkout_url) WHERE id=? AND user_id=? AND state IN ('creating','created','unconfirmed')",
            state, providerId, url, id, userId);
    }
    @Override public ArrayNode listPayments(String userId) {
        return safe(() -> {
            ArrayNode items = Json.arr();
            jdbc.query("SELECT * FROM fc_payment_links WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 20", paymentMapper(), userId).forEach(row -> {
                row.remove("providerId"); items.add(row);
            });
            return items;
        });
    }
    @Override public boolean applyPaymentEvent(String eventId, String type, String reference, String providerId, int amount, String currency, String state) {
        return tx(() -> {
            quotaLock();
            var rows = jdbc.query("SELECT * FROM fc_payment_links WHERE id=? FOR UPDATE", paymentMapper(), reference);
            if (rows.isEmpty()) throw ApiError.bad("The payment event does not match an existing payment link.");
            ObjectNode row = rows.getFirst();
            if (row.path("amountPaise").intValue() != amount || !Json.text(row, "currency").equals(currency) ||
                (!row.path("providerId").isNull() && !Json.text(row, "providerId").equals(providerId))) throw ApiError.bad("The payment event does not match its amount, currency, or provider reference.");
            var events = jdbc.queryForList("SELECT payment_link_id FROM fc_payment_events WHERE event_id=?", String.class, eventId);
            if (!events.isEmpty()) {
                if (!events.getFirst().equals(reference)) throw ApiError.bad("The payment event identifier was already used for another link.");
                return false;
            }
            if (count("SELECT COUNT(*) FROM fc_payment_events") >= 50000) throw new ApiError(503, "Payment audit storage requires operator maintenance.", "payment_capacity");
            jdbc.update("INSERT INTO fc_payment_events(event_id,payment_link_id,event_type) VALUES (?,?,?)", eventId, reference, type);
            if (!Json.text(row, "state").equals("paid") || state.equals("paid")) jdbc.update("UPDATE fc_payment_links SET state=?,provider_id=? WHERE id=?", state, providerId, reference);
            return true;
        });
    }
}
