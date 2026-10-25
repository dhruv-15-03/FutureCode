package io.futurecode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.function.Consumer;

public interface Store {
    record User(String id, String email, String name, String displayName, String role) {}
    record Identity(String sub, String email, String name) {}
    record StoredSession(User user, String csrfHash, Instant authenticatedAt) {}
    record Cursor(Instant at, String id) {}
    record Filters(String status, String authorId, Cursor cursor, String search, String difficulty, String language) {}
    record Page(ArrayNode items, boolean more) {}

    void ping();
    User upsertGoogleUser(Identity identity);
    void createSession(String userId, String tokenHash, String csrfHash, Instant expiresAt);
    StoredSession getSession(String tokenHash);
    void removeSession(String tokenHash);
    void cleanSessions();
    void updateProfile(String userId, String displayName);
    void deleteAccount(String userId);
    Page listProblems(Filters filters);
    ObjectNode getProblem(String id);
    ObjectNode saveProblem(String userId, JsonNode body, String id, Integer revision);
    ObjectNode submitProblem(String userId, String id, int revision, Consumer<JsonNode> validate);
    ObjectNode withdrawProblem(String userId, String id, int revision);
    ObjectNode reviewProblem(String adminId, String id, int revision, String decision, String note, Consumer<JsonNode> validate);
    void removeProblem(User actor, String id, int revision);
    ObjectNode saveAttempt(String userId, JsonNode attempt);
    ArrayNode listAttempts(String userId);
    ObjectNode getAttempt(String userId, String id);
    void removeAttempt(String userId, String id);
    boolean hasAttempt(String userId, String problemKey);
    void grantAdmin(String userId);
    ArrayNode listBookmarks(String userId);
    boolean hasBookmark(String userId, String problemKey);
    void setBookmark(String userId, String problemKey, String title, boolean saved);
    ObjectNode votes(String problemKey, String userId);
    void vote(String userId, String problemKey, int value);
    Page listDiscussions(String problemKey, Cursor cursor, boolean moderator);
    ObjectNode getDiscussion(String id);
    ObjectNode saveDiscussion(String userId, String problemKey, String text, String id, Integer revision);
    void removeDiscussion(User actor, String id, int revision);
    ObjectNode moderateDiscussion(String adminId, String id, int revision, boolean hidden, String note);
    Page listAudit(Cursor cursor);
    boolean leaderboardOptIn(String userId);
    void setLeaderboardOptIn(String userId, boolean enabled);
    ArrayNode leaderboard();
    ObjectNode reserveRun(String userId, String problemKey, String kind, String language, String sourceHash);
    void updateRun(String userId, String id, String state, String providerToken, JsonNode result);
    ObjectNode getRun(String userId, String id);
    ArrayNode listRuns(String userId);
    void removeRun(String userId, String id);
    ObjectNode reservePayment(String userId, String requestId, int amountPaise);
    void updatePayment(String userId, String id, String state, String providerId, String checkoutUrl);
    ArrayNode listPayments(String userId);
    boolean applyPaymentEvent(String eventId, String eventType, String referenceId, String providerId, int amountPaise, String currency, String state);
}
