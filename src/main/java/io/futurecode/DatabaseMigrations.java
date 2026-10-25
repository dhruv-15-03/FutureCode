package io.futurecode;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.Set;

public final class DatabaseMigrations {
    private final DataSource source;
    private final JdbcTemplate jdbc;
    private final Flyway flyway;
    public DatabaseMigrations(DataSource source, JdbcTemplate jdbc, AppSettings settings) {
        this.source = source; this.jdbc = jdbc;
        flyway = Flyway.configure().dataSource(source).table("fc_flyway_schema_history").locations("classpath:db/migration")
            .baselineOnMigrate(false).cleanDisabled(true)
            .schemas(settings.database().schema()).createSchemas(false).load();
    }
    public void verify() {
        try {
            if (!table("fc_flyway_schema_history") || flyway.info().current() == null ||
                !"3".equals(flyway.info().current().getVersion().getVersion())) {
                throw new IllegalStateException("Database migrations are required. Run the migrate command before serving.");
            }
            flyway.validate();
            verifyCore(true);
            verifyExtensions();
        } catch (org.flywaydb.core.api.FlywayException | org.springframework.dao.DataAccessException error) {
            throw new IllegalStateException("Database schema verification failed. Check connectivity and run the migrate command. No credentials were logged.");
        }
    }
    public void migrate() {
        try (Connection connection = lockConnection()) {
            boolean locked = false;
            try {
                try (PreparedStatement query = connection.prepareStatement("SELECT GET_LOCK('futurecode_schema_migrations',10)")) {
                    query.setQueryTimeout(12);
                    try (ResultSet rows = query.executeQuery()) { locked = rows.next() && rows.getInt(1) == 1; }
                }
                if (!locked) throw new IllegalStateException("Another schema migration is running. Retry later.");
                if (table("fc_schema_migrations") && !table("fc_flyway_schema_history")) {
                    var applied = jdbc.queryForList("SELECT version,checksum FROM fc_schema_migrations");
                    if (applied.size() != 1 || !"001_core".equals(applied.getFirst().get("version")) ||
                        !Crypto.sha(coreSource()).equals(applied.getFirst().get("checksum"))) {
                        throw new IllegalStateException("The existing Node schema is not the known, checksum-matching version. No baseline or destructive change was performed.");
                    }
                    verifyCore(false);
                    flyway.baseline();
                }
                flyway.migrate();
                verify();
            } finally {
                if (locked) try (PreparedStatement release = connection.prepareStatement("SELECT RELEASE_LOCK('futurecode_schema_migrations')")) { release.execute(); }
            }
        } catch (java.sql.SQLException | org.flywaydb.core.api.FlywayException | org.springframework.dao.DataAccessException error) {
            throw new IllegalStateException("Migration failed. Check connectivity, verified TLS, and schema state. MySQL DDL is not rolled back; no success was confirmed.");
        }
    }
    private Connection lockConnection() throws java.sql.SQLException {
        // The CLI lock must not exhaust a one-connection application pool.
        if (source instanceof com.zaxxer.hikari.HikariDataSource pool) {
            var lockSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(pool.getJdbcUrl(), pool.getUsername(), pool.getPassword());
            lockSource.setConnectionProperties(pool.getDataSourceProperties());
            return lockSource.getConnection();
        }
        return source.getConnection();
    }
    static String coreSource() {
        try (var input = new org.springframework.core.io.ClassPathResource("db/migration/V1__core.sql").getInputStream()) {
            return new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
        } catch (java.io.IOException error) { throw new IllegalStateException("The packaged core schema is missing."); }
    }
    private boolean table(String name) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name=?", Long.class, name);
        return count != null && count == 1;
    }
    private void verifyCore(boolean community) {
        Map<String, Set<String>> required = Map.of(
            "fc_app_lock", Set.of("id"),
            "fc_users", Set.of("id", "google_sub", "email", "google_name", "display_name", "role", "created_at", "updated_at"),
            "fc_sessions", Set.of("token_hash", "user_id", "csrf_hash", "expires_at", "created_at"),
            "fc_problems", Set.of("id", "author_id", "title", "language", "difficulty", "kind", "status", "body", "revision", "review_note", "reviewed_by", "published_at", "created_at", "updated_at"),
            "fc_attempts", Set.of("id", "user_id", "problem_key", "title", "problem_version", "category", "score", "max_score", "record", "created_at"));
        for (var entry : required.entrySet()) {
            Set<String> columns = Set.copyOf(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=?", String.class, entry.getKey()));
            Set<String> expectedColumns = new java.util.HashSet<>(entry.getValue());
            if (community && entry.getKey().equals("fc_users")) expectedColumns.add("leaderboard_opt_in");
            if (!columns.equals(expectedColumns)) throw new IllegalStateException("The owned schema does not match its expected structure. Migration stopped without a baseline.");
            Set<String> unique = Set.copyOf(jdbc.queryForList(
                "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? AND non_unique=0 GROUP BY index_name",
                String.class, entry.getKey()));
            Set<String> expected = entry.getKey().equals("fc_users") ? Set.of("id", "google_sub") :
                entry.getKey().equals("fc_sessions") ? Set.of("token_hash") : Set.of("id");
            if (!unique.equals(expected)) throw new IllegalStateException("The owned schema's identity or primary-key constraints changed. Migration stopped without a baseline.");
        }
        Set<String> foreignKeys = Set.copyOf(jdbc.queryForList(
            "SELECT CONCAT(k.table_name,'.',k.column_name,'->',k.referenced_table_name,'.',k.referenced_column_name,':',r.delete_rule) " +
            "FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r ON r.constraint_schema=k.constraint_schema AND r.constraint_name=k.constraint_name AND r.table_name=k.table_name " +
            "WHERE k.constraint_schema=DATABASE() AND k.table_name IN ('fc_sessions','fc_problems','fc_attempts') AND k.referenced_table_name IS NOT NULL", String.class));
        if (!foreignKeys.equals(Set.of("fc_sessions.user_id->fc_users.id:CASCADE", "fc_problems.author_id->fc_users.id:CASCADE",
            "fc_problems.reviewed_by->fc_users.id:SET NULL", "fc_attempts.user_id->fc_users.id:CASCADE"))) {
            throw new IllegalStateException("The owned schema's deletion/ownership constraints changed. Migration stopped without a baseline.");
        }
        Integer lock = jdbc.queryForObject("SELECT id FROM fc_app_lock WHERE id=1", Integer.class);
        if (lock == null || lock != 1) throw new IllegalStateException("The schema quota lock is missing.");
    }
    private void verifyExtensions() {
        Map<String, Set<String>> required = Map.of(
            "fc_bookmarks", Set.of("user_id", "problem_key", "title", "created_at"),
            "fc_discussions", Set.of("id", "user_id", "problem_key", "body", "hidden", "revision", "moderation_note", "created_at", "updated_at"),
            "fc_problem_votes", Set.of("user_id", "problem_key", "value", "created_at"),
            "fc_moderation_audit", Set.of("id", "actor_id", "action", "target_key", "details", "created_at"),
            "fc_runs", Set.of("id", "user_id", "problem_key", "kind", "language", "state", "source_hash", "provider_token", "result", "created_at", "updated_at"),
            "fc_payment_links", Set.of("id", "user_id", "request_id", "provider_id", "amount_paise", "currency", "state", "checkout_url", "expires_at", "created_at", "updated_at"),
            "fc_payment_events", Set.of("event_id", "payment_link_id", "event_type", "created_at"));
        for (var entry : required.entrySet()) {
            Set<String> columns = Set.copyOf(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=?", String.class, entry.getKey()));
            if (!columns.equals(entry.getValue())) throw new IllegalStateException("The community schema is incomplete. Run the owned migrations.");
            Set<String> unique = Set.copyOf(jdbc.queryForList(
                "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',') FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name=? AND non_unique=0 GROUP BY index_name",
                String.class, entry.getKey()));
            Set<String> expected = switch (entry.getKey()) {
                case "fc_bookmarks", "fc_problem_votes" -> Set.of("user_id,problem_key");
                case "fc_payment_links" -> Set.of("id", "provider_id", "user_id,request_id");
                case "fc_payment_events" -> Set.of("event_id");
                default -> Set.of("id");
            };
            if (!unique.equals(expected)) throw new IllegalStateException("The extension schema's identity or idempotency constraints changed.");
        }
        Set<String> foreignKeys = Set.copyOf(jdbc.queryForList(
            "SELECT CONCAT(k.table_name,'.',k.column_name,'->',k.referenced_table_name,'.',k.referenced_column_name,':',r.delete_rule) " +
            "FROM information_schema.key_column_usage k JOIN information_schema.referential_constraints r ON r.constraint_schema=k.constraint_schema AND r.constraint_name=k.constraint_name AND r.table_name=k.table_name " +
            "WHERE k.constraint_schema=DATABASE() AND k.table_name IN ('fc_bookmarks','fc_discussions','fc_problem_votes','fc_moderation_audit','fc_runs','fc_payment_links','fc_payment_events') AND k.referenced_table_name IS NOT NULL", String.class));
        if (!foreignKeys.equals(Set.of("fc_bookmarks.user_id->fc_users.id:CASCADE", "fc_discussions.user_id->fc_users.id:CASCADE",
            "fc_problem_votes.user_id->fc_users.id:CASCADE", "fc_moderation_audit.actor_id->fc_users.id:SET NULL",
            "fc_runs.user_id->fc_users.id:CASCADE", "fc_payment_links.user_id->fc_users.id:SET NULL",
            "fc_payment_events.payment_link_id->fc_payment_links.id:RESTRICT"))) {
            throw new IllegalStateException("The extension schema's ownership, deletion or accounting constraints changed.");
        }
    }
}
