package io.futurecode;

import org.springframework.boot.ExitCodeGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.ObjectNode;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DatabaseSetup implements ExitCodeGenerator {
    static final String REPORT_PREFIX = "FUTURECODE_SETUP_REPORT:";
    private final AppSettings settings;
    private final CuratedContent content;
    private ObjectNode result;
    private enum Phase { CONFIGURATION, CONNECTION, SCHEMA, MIGRATION, VERIFICATION }

    public DatabaseSetup(AppSettings settings, CuratedContent content) { this.settings = settings; this.content = content; }

    public void run() {
        Phase phase = Phase.CONFIGURATION;
        boolean connected = false, tls = false, created = false;
        String schema = "";
        try {
            AppSettings.Database database = settings.database();
            schema = database.schema();
            phase = Phase.CONNECTION;
            try (var bootstrap = new PersistenceConfiguration.DatabasePool(settings, "", true);
                 var connection = bootstrap.source.getConnection()) {
                if (!connection.isValid(5)) throw new SQLException("Database validation failed.");
                connected = true;
                if (connection.getMetaData().getDatabaseMajorVersion() < 8) throw new SetupFailure("mysql_8_required");
                try (var query = connection.prepareStatement("SHOW SESSION STATUS LIKE 'Ssl_cipher'")) {
                    query.setQueryTimeout(8);
                    try (var rows = query.executeQuery()) { tls = rows.next() && rows.getString(2) != null && !rows.getString(2).isBlank(); }
                }
                if (database.tls() && !tls) throw new SetupFailure("verified_tls_required");
                phase = Phase.SCHEMA;
                boolean exists;
                try (var query = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?")) {
                    query.setQueryTimeout(8); query.setString(1, schema);
                    try (var rows = query.executeQuery()) { exists = rows.next() && rows.getInt(1) > 0; }
                }
                if (!exists) {
                    try (var query = connection.createStatement()) {
                        query.setQueryTimeout(8);
                        query.execute("CREATE DATABASE IF NOT EXISTS " + identifier(schema) + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
                    }
                    created = true;
                }
            }
            phase = Phase.MIGRATION;
            int tables;
            try (var target = new PersistenceConfiguration.DatabasePool(settings, schema, true)) {
                JdbcTemplate jdbc = new JdbcTemplate(target.source); jdbc.setQueryTimeout(8);
                DatabaseMigrations migrations = new DatabaseMigrations(target.source, jdbc, settings);
                migrations.migrate();
                phase = Phase.VERIFICATION;
                verifyRepository(jdbc, target.source);
                Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name LIKE 'fc!_%' ESCAPE '!'", Integer.class);
                if (count == null || count < 13) throw new SetupFailure("schema_verification_failed");
                tables = count;
            }
            result = report(true, "verified", schema, connected, tls, created);
            result.put("applicationTables", tables).put("migrationsCurrent", true).put("rollbackProbeVerified", true);
        } catch (SQLException | RuntimeException error) {
            // Driver and framework exceptions may contain credentials; never print their text or stack.
            result = report(false, failureCode(phase, error), schema, connected, tls, created);
            if (phase == Phase.CONFIGURATION) result.set("configurationChecks", configurationChecks());
            if (result.path("code").asString().equals("tls_verification_failed")) result.put("tlsFailureKind", tlsFailureKind(error));
        }
        System.out.println(REPORT_PREFIX + Json.stringify(result));
    }

    private ObjectNode report(boolean ok, String code, String schema, boolean connected, boolean tls, boolean created) {
        return Json.obj("ok", ok, "code", code, "schema", schema, "databaseConnected", connected, "encryptedConnection", tls, "schemaCreated", created,
            "caCertificateConfigured", !settings.mysqlCaFile().isBlank(),
            "googleConfigurationComplete", settings.loginConfigurationIssues().isEmpty(), "configurationIssues", settings.loginConfigurationIssues(),
            "googleSignInLiveVerified", false);
    }
    private ObjectNode configurationChecks() {
        ObjectNode checks = Json.obj("databaseUrlPresent", settings.databaseConfigured(),
            "jdbcPrefixDetected", settings.databaseUrl().startsWith("jdbc:mysql:"),
            "tlsSettingAccepted", settings.mysqlTls().isBlank() || java.util.Set.of("required", "disabled").contains(settings.mysqlTls()));
        try {
            var uri = java.net.URI.create(settings.databaseUrl());
            String userInfo = uri.getRawUserInfo();
            boolean local = uri.getHost() != null && java.util.Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(uri.getHost());
            checks.put("urlParseable", true).put("expectedScheme", "mysql".equals(uri.getScheme()))
                .put("hostPresent", uri.getHost() != null).put("credentialsPresent", userInfo != null && userInfo.contains(":"))
                .put("queryAbsent", uri.getRawQuery() == null).put("queryPolicyAccepted", AppSettings.tlsQueryAccepted(uri.getRawQuery())).put("fragmentAbsent", uri.getFragment() == null)
                .put("schemaPathAccepted", uri.getPath() != null && (uri.getPath().matches("/[A-Za-z0-9_]{1,64}") ||
                    (!settings.databaseSchema().isEmpty() && java.util.Set.of("", "/").contains(uri.getPath()))))
                .put("remoteTlsAllowed", local && !settings.production() || !settings.mysqlTls().equals("disabled"))
                .put("portAccepted", uri.getPort() == -1 || (uri.getPort() > 0 && uri.getPort() <= 65535));
        } catch (IllegalArgumentException error) { checks.put("urlParseable", false); }
        return checks;
    }

    private void verifyRepository(JdbcTemplate jdbc, javax.sql.DataSource source) {
        var manager = new DataSourceTransactionManager(source);
        var transaction = new TransactionTemplate(manager); transaction.setTimeout(45);
        JdbcStore store = new JdbcStore(jdbc, new TransactionTemplate(manager), settings);
        String subject = "futurecode-setup-" + UUID.randomUUID();
        String paymentId = transaction.execute(status -> {
            Store.User user = store.upsertGoogleUser(new Store.Identity(subject, "setup-check@example.invalid", "Setup verification"));
            String token = Crypto.sha(Crypto.random()), csrf = Crypto.sha(Crypto.random());
            store.createSession(user.id(), token, csrf, Instant.now().plusSeconds(120));
            if (store.getSession(token) == null) throw new SetupFailure("session_verification_failed");
            store.updateProfile(user.id(), "Setup verification");
            ObjectNode problem = probeProblem();
            ProblemContract.validate(problem, true);
            ObjectNode draft = store.saveProblem(user.id(), problem, null, null);
            String id = Json.text(draft, "id");
            ObjectNode pending = store.submitProblem(user.id(), id, 1, value -> ProblemContract.validate(value, true));
            if (!Json.text(pending, "status").equals("pending")) throw new SetupFailure("publication_verification_failed");
            store.grantAdmin(user.id());
            ObjectNode published = store.reviewProblem(user.id(), id, 2, "publish", "", value -> ProblemContract.validate(value, true));
            int revision = Json.integer(published, "revision", -1);
            var graded = new GradingService(content).grade(Json.obj("problemKey", "community:" + id,
                "payload", Json.obj("revision", revision, "answer", 0, "explanation", "")), store);
            var saved = store.saveAttempt(user.id(), graded.path("attempt"));
            if (saved.path("score").intValue() != 1 || store.getAttempt(user.id(), Json.text(saved, "id")).path("record").isMissingNode()) throw new SetupFailure("attempt_verification_failed");
            if (store.listProblems(new Store.Filters("published", user.id(), null, "", "", "")).items().size() != 1 ||
                !store.hasAttempt(user.id(), "community:" + id)) throw new SetupFailure("record_verification_failed");
            String createdPaymentId = verifyExtensions(store, jdbc, user, subject, "community:" + id);
            store.deleteAccount(user.id());
            if (store.getSession(token) != null || !store.listAttempts(user.id()).isEmpty() ||
                !store.listProblems(new Store.Filters(null, user.id(), null, "", "", "")).items().isEmpty()) throw new SetupFailure("deletion_verification_failed");
            status.setRollbackOnly();
            return createdPaymentId;
        });
        Long left = jdbc.queryForObject("SELECT COUNT(*) FROM fc_users WHERE google_sub IN (?,?)", Long.class, subject, subject + "-reader");
        Long payments = jdbc.queryForObject("SELECT COUNT(*) FROM fc_payment_links WHERE id=?", Long.class, paymentId);
        if (left == null || left != 0 || paymentId == null || payments == null || payments != 0) throw new SetupFailure("rollback_verification_failed");
    }

    private String verifyExtensions(JdbcStore store, JdbcTemplate jdbc, Store.User moderator, String subject, String problemKey) {
        Store.User reader = store.upsertGoogleUser(new Store.Identity(subject + "-reader", "setup-reader@example.invalid", "Setup reader"));
        String name = "Setup reader " + reader.id();
        store.updateProfile(reader.id(), name);
        store.setBookmark(reader.id(), problemKey, "Setup verification", true);
        store.setBookmark(reader.id(), problemKey, "Setup verification", true);
        if (!store.hasBookmark(reader.id(), problemKey) || store.listBookmarks(reader.id()).size() != 1) throw new SetupFailure("bookmark_verification_failed");
        store.vote(reader.id(), problemKey, -1);
        if (store.votes(problemKey, reader.id()).path("mine").intValue() != -1) throw new SetupFailure("vote_verification_failed");
        store.vote(reader.id(), problemKey, 1);
        if (store.votes(problemKey, reader.id()).path("score").intValue() != 1) throw new SetupFailure("vote_verification_failed");
        var problem = store.getProblem(problemKey.substring("community:".length()));
        GradingService grading = new GradingService(content);
        store.saveAttempt(reader.id(), grading.grade(Json.obj("problemKey", problemKey, "payload",
            Json.obj("revision", problem.path("revision"), "answer", 0, "explanation", "")), store).path("attempt"));
        var drill = content.challenges().get(0);
        var attempt = grading.grade(Json.obj("problemKey", "drill:" + Json.text(drill, "id"),
            "payload", Json.obj("answer", drill.path("questions").get(0).path("correctIndex"))), store).path("attempt");
        store.saveAttempt(reader.id(), attempt); store.saveAttempt(reader.id(), attempt);
        if (store.leaderboardOptIn(reader.id())) throw new SetupFailure("leaderboard_privacy_verification_failed");
        store.setLeaderboardOptIn(reader.id(), true);
        Integer best = jdbc.queryForObject("SELECT SUM(best) FROM (SELECT MAX(score) AS best FROM fc_attempts WHERE user_id=? AND category='curated' GROUP BY problem_key) matched", Integer.class, reader.id());
        boolean boardVerified = best != null && best == 1;
        for (var row : store.leaderboard()) {
            if (Json.text(row, "displayName").equals(name) && (row.path("entries").intValue() != 1 || row.path("curatedChecks").intValue() != 1)) boardVerified = false;
        }
        if (!store.leaderboardOptIn(reader.id()) || !boardVerified) throw new SetupFailure("leaderboard_verification_failed");
        var comment = store.saveDiscussion(reader.id(), problemKey, "Rollback-only discussion check.", null, null);
        String commentId = Json.text(comment, "id");
        store.moderateDiscussion(moderator.id(), commentId, 1, true, "Rollback-only visibility check.");
        if (!store.listDiscussions(problemKey, null, false).items().isEmpty() ||
            store.listDiscussions(problemKey, null, true).items().size() != 1) throw new SetupFailure("discussion_verification_failed");
        store.moderateDiscussion(moderator.id(), commentId, 2, false, "Rollback-only restore check.");
        store.saveDiscussion(reader.id(), problemKey, "Rollback-only revised discussion.", commentId, 3);
        if (store.getDiscussion(commentId).path("revision").intValue() != 4 || store.listAudit(null).items().isEmpty()) throw new SetupFailure("audit_verification_failed");
        var run = store.reserveRun(reader.id(), "case:" + Json.text(content.practiceCase(), "id"), "counter-regression", "java", Crypto.sha("repository-storage-probe"));
        String runId = Json.text(run, "id");
        store.updateRun(reader.id(), runId, "completed", UUID.randomUUID().toString(),
            Json.obj("status", "Synthetic storage verification only", "notice", "No code was sent or executed.", "stdout", ""));
        if (!Json.text(store.getRun(reader.id(), runId), "state").equals("completed") || store.listRuns(reader.id()).size() != 1 ||
            store.listRuns(reader.id()).get(0).has("providerToken")) throw new SetupFailure("execution_record_verification_failed");
        String requestId = UUID.randomUUID().toString();
        var payment = store.reservePayment(reader.id(), requestId, 100);
        String paymentId = Json.text(payment, "id"), providerId = "plnk_setup_" + UUID.randomUUID();
        if (!payment.path("newlyCreated").booleanValue() || store.reservePayment(reader.id(), requestId, 100).path("newlyCreated").booleanValue()) throw new SetupFailure("payment_idempotency_verification_failed");
        store.updatePayment(reader.id(), paymentId, "created", providerId, "https://rzp.io/i/setup-storage-check");
        String eventId = "setup-" + UUID.randomUUID();
        if (!store.applyPaymentEvent(eventId, "payment_link.paid", paymentId, providerId, 100, "INR", "paid") ||
            store.applyPaymentEvent(eventId, "payment_link.paid", paymentId, providerId, 100, "INR", "paid")) throw new SetupFailure("payment_event_verification_failed");
        store.applyPaymentEvent("setup-" + UUID.randomUUID(), "payment_link.expired", paymentId, providerId, 100, "INR", "expired");
        if (!Json.text(store.listPayments(reader.id()).get(0), "state").equals("paid")) throw new SetupFailure("payment_state_verification_failed");
        store.deleteAccount(reader.id());
        Integer retained = jdbc.queryForObject("SELECT COUNT(*) FROM fc_payment_links WHERE id=? AND user_id IS NULL AND state='paid'", Integer.class, paymentId);
        Integer discussions = jdbc.queryForObject("SELECT COUNT(*) FROM fc_discussions WHERE id=?", Integer.class, commentId);
        if (!store.listBookmarks(reader.id()).isEmpty() || !store.listRuns(reader.id()).isEmpty() ||
            store.votes(problemKey, null).path("score").intValue() != 0 || discussions == null || discussions != 0 || retained == null || retained != 1) throw new SetupFailure("extension_deletion_verification_failed");
        return paymentId;
    }

    private static ObjectNode probeProblem() {
        return Json.obj("title", "Setup verification", "statement", "What is the assigned value?", "language", "Java",
            "difficulty", "introductory", "kind", "reading", "files", List.of(Json.obj("name", "Probe.java", "source", "int value = 1;")),
            "responseMode", "choice", "options", List.of("1", "2", "3", "4"), "correctIndex", 0, "rightsConfirmed", true,
            "reference", Json.obj("explanation", "The literal assignment uses 1.", "solution", "", "verification", "Inspect the literal; no source is executed.",
                "evidence", List.of(Json.obj("file", "Probe.java", "line", 1, "quote", "int value = 1;"))));
    }
    static String identifier(String schema) {
        if (schema == null || !schema.matches("[A-Za-z0-9_]{1,64}")) throw new IllegalArgumentException("Invalid MySQL schema name.");
        return "`" + schema + "`";
    }
    private static String tlsFailureKind(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof java.security.cert.CertificateExpiredException) return "expired_certificate";
            if (current instanceof java.security.cert.CertificateNotYetValidException) return "certificate_not_yet_valid";
            if (current instanceof java.security.cert.CertPathBuilderException || current instanceof java.security.cert.CertPathValidatorException) return "untrusted_certificate_chain";
            if (current instanceof java.security.cert.CertificateException) {
                String message = current.getMessage();
                if (message != null && (message.contains("No name matching") || message.contains("No subject alternative"))) return "hostname_mismatch";
            }
        }
        return "handshake_verification_failed";
    }
    private static String failureCode(Phase phase, Throwable error) {
        if (error instanceof SetupFailure failure) return failure.code;
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof javax.net.ssl.SSLException || current instanceof java.security.cert.CertificateException) return "tls_verification_failed";
            if (current instanceof SQLException sql) {
                if (sql.getErrorCode() == 1045 || "28000".equals(sql.getSQLState())) return "database_authentication_failed";
                if (phase == Phase.SCHEMA && (sql.getErrorCode() == 1044 || sql.getErrorCode() == 1142 || sql.getErrorCode() == 1227)) return "schema_creation_denied";
            }
        }
        return switch (phase) {
            case CONFIGURATION -> "configuration_invalid";
            case CONNECTION -> "database_connection_failed";
            case SCHEMA -> "schema_creation_failed";
            case MIGRATION -> "schema_migration_failed";
            case VERIFICATION -> "database_verification_failed";
        };
    }
    @Override public int getExitCode() { return result == null || result.path("ok").booleanValue() ? 0 : 1; }
    private static final class SetupFailure extends RuntimeException {
        final String code;
        SetupFailure(String code) { super("Database setup verification failed."); this.code = code; }
    }
}
