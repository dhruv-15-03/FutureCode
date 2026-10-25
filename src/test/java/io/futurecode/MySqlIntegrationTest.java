package io.futurecode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@EnabledIfEnvironmentVariable(named = "FUTURECODE_ALLOW_TEST_WRITES", matches = "yes")
class MySqlIntegrationTest {
    @Test void ownedMySqlSchemaSessionsTransactionsPublicationAndPrivateRecordsWorkWithAOneConnectionPool() throws Exception {
        String url = System.getenv("FUTURECODE_TEST_DATABASE_URL");
        assumeTrue(url != null && !url.isBlank(), "A dedicated test MySQL URL is required.");
        assertTrue(URI.create(url).getPath().matches("/[A-Za-z0-9_]+_test"), "Only a dedicated schema ending in _test is permitted.");
        AppSettings settings = new AppSettings("migrate", "", "development", "http://127.0.0.1:4317", "127.0.0.1", url, "",
            System.getenv().getOrDefault("MYSQL_TLS", ""), System.getenv().getOrDefault("MYSQL_CA_FILE", ""), 1,
            "", "", "", 10000, 10000, 10, 20, 8);
        try (var pool = new PersistenceConfiguration.DatabasePool(settings)) {
            JdbcTemplate jdbc = new JdbcTemplate(pool.source);
            DatabaseMigrations migrations = new DatabaseMigrations(pool.source, jdbc, settings); migrations.migrate(); migrations.migrate();
            JdbcStore store = new JdbcStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(pool.source)), settings);
            Store.User author = store.upsertGoogleUser(new Store.Identity("fc-test-" + UUID.randomUUID(), "author@example.test", "Fixture author"));
            Store.User other = store.upsertGoogleUser(new Store.Identity("fc-test-" + UUID.randomUUID(), "other@example.test", "Fixture other"));
            try {
                assertEquals("student", author.role()); store.updateProfile(author.id(), "Public fixture");
                String token = Crypto.sha("fc-test-" + UUID.randomUUID()), csrf = Crypto.sha("fc-test-csrf");
                store.createSession(author.id(), token, csrf, Instant.now().plusSeconds(3600));
                var session = store.getSession(token); assertNotNull(session); assertEquals("Public fixture", session.user().displayName());
                assertTrue(Math.abs(Instant.now().toEpochMilli() - session.authenticatedAt().toEpochMilli()) < 10000);
                store.removeSession(token); assertNull(store.getSession(token));
                var draft = store.saveProblem(author.id(), TestData.problem(), null, null); String id = Json.text(draft, "id");
                var pending = store.submitProblem(author.id(), id, 1, value -> ProblemContract.validate(value, true));
                assertEquals("pending", Json.text(pending, "status"));
                assertEquals(409, assertThrows(ApiError.class, () -> store.withdrawProblem(author.id(), id, 1)).status());
                var withdrawn = store.withdrawProblem(author.id(), id, 2); assertEquals("draft", Json.text(withdrawn, "status"));
                store.submitProblem(author.id(), id, 3, value -> ProblemContract.validate(value, true));
                store.grantAdmin(other.id());
                var published = store.reviewProblem(other.id(), id, 4, "publish", "", value -> ProblemContract.validate(value, true));
                assertEquals("published", Json.text(published, "status"));
                assertEquals(1, store.listProblems(new Store.Filters("published", author.id(), null, "", "", "")).items().size());
                var graded = new GradingService(new CuratedContent()).grade(Json.obj("problemKey", "community:" + id, "payload", Json.obj("revision", 5, "answer", 0, "explanation", "")), store);
                var saved = store.saveAttempt(author.id(), graded.path("attempt")); assertEquals(1, saved.path("score").intValue());
                String attemptId = Json.text(saved, "id");
                assertEquals(404, assertThrows(ApiError.class, () -> store.getAttempt(other.id(), attemptId)).status());
                assertTrue(store.hasAttempt(author.id(), "community:" + id));
                store.removeAttempt(author.id(), attemptId); assertFalse(store.hasAttempt(author.id(), "community:" + id));
                assertEquals(403, assertThrows(ApiError.class, () -> store.removeProblem(author, id, 5)).status());
                store.removeProblem(new Store.User(other.id(), other.email(), other.name(), other.displayName(), "admin"), id, 5);
                assertEquals(404, assertThrows(ApiError.class, () -> store.getProblem(id)).status());
            } finally {
                store.deleteAccount(author.id()); store.deleteAccount(other.id());
            }
        }
    }
}
