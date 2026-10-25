package io.futurecode;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

@ConfigurationProperties("futurecode")
public record AppSettings(
    String command, String adminUserId, String environment, String origin, String bindAddress,
    String databaseUrl, String databaseSchema, String mysqlTls, String mysqlCaFile, int connectionLimit,
    String googleClientId, String googleClientSecret, String sessionSecret,
    int maxUsers, int maxProblems, int maxProblemsPerUser, int maxSavedAttempts,
    int providerConcurrency
) {
    public AppSettings {
        if (!Set.of("serve", "migrate", "admin", "provision").contains(command)) fail("Use serve, migrate, admin, or provision as the command.");
        if (databaseSchema == null) databaseSchema = "";
        if (!databaseSchema.isEmpty() && !databaseSchema.matches("[A-Za-z0-9_]{1,64}")) fail("DATABASE_SCHEMA must be a plain MySQL schema name of at most 64 characters.");
        URI uri;
        try { uri = URI.create(origin); }
        catch (IllegalArgumentException error) { throw new IllegalArgumentException("APP_ORIGIN must be a valid origin."); }
        if (uri.getHost() == null || !Set.of("http", "https").contains(uri.getScheme()) ||
            uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null ||
            !(uri.getPath().isEmpty() || uri.getPath().equals("/"))) fail("APP_ORIGIN must contain only scheme, hostname, and optional port.");
        origin = origin.replaceAll("/$", "");
        boolean production = environment.equals("production");
        boolean serving = command.equals("serve");
        if (serving && production && !uri.getScheme().equals("https")) fail("Production APP_ORIGIN must use HTTPS.");
        if (serving && !production && (!Set.of("127.0.0.1", "localhost", "::1", "[::1]").contains(uri.getHost()) ||
            !Set.of("127.0.0.1", "localhost", "::1", "[::1]").contains(bindAddress))) fail("Development preview must bind to loopback. Use complete production configuration for a public host.");
        if (connectionLimit < 1 || connectionLimit > 8 || maxUsers < 1 || maxUsers > 10000 ||
            maxProblems < 1 || maxProblems > 10000 || maxProblemsPerUser < 1 || maxProblemsPerUser > 100 ||
            maxSavedAttempts < 1 || maxSavedAttempts > 100 || providerConcurrency < 1 || providerConcurrency > 16) {
            fail("A resource limit is outside its supported range.");
        }
        boolean googleAny = !googleClientId.isBlank() || !googleClientSecret.isBlank();
        if (serving && googleAny && (!googleClientId.matches("[A-Za-z0-9._-]+\\.apps\\.googleusercontent\\.com") ||
            !googleClientSecret.matches("[!-~]{1,512}") || !sessionSecret.matches("[!-~]{32,256}"))) {
            fail("Google sign-in requires a web-client ID, a single printable OAuth secret, and a random SESSION_SECRET of 32-256 printable characters.");
        }
        if (production && command.equals("serve") && (!googleAny || databaseUrl.isBlank() || sessionSecret.length() < 32)) {
            fail("Production requires MySQL and complete Google/session configuration. Preview is development-only.");
        }
        if (!mysqlCaFile.isBlank() && !Files.isRegularFile(Path.of(mysqlCaFile))) fail("MYSQL_CA_FILE must point to a readable CA certificate file.");
    }

    public boolean production() { return environment.equals("production"); }
    public boolean databaseConfigured() { return !databaseUrl.isBlank(); }
    public boolean authConfigured() { return databaseConfigured() && loginConfigurationIssues().isEmpty(); }
    public java.util.List<String> loginConfigurationIssues() {
        java.util.List<String> issues = new java.util.ArrayList<>();
        if (!googleClientId.matches("[A-Za-z0-9._-]+\\.apps\\.googleusercontent\\.com")) issues.add("GOOGLE_CLIENT_ID");
        if (!googleClientSecret.matches("[!-~]{1,512}")) issues.add("GOOGLE_CLIENT_SECRET");
        if (!sessionSecret.matches("[!-~]{32,256}")) issues.add("SESSION_SECRET");
        return java.util.List.copyOf(issues);
    }
    public String sessionCookie() { return production() ? "__Host-futurecode_session" : "futurecode_session"; }
    public String flowCookie() { return production() ? "__Host-futurecode_oauth" : "futurecode_oauth"; }
    public String callbackUri() { return origin + "/auth/google/callback"; }

    public Database database() {
        if (!databaseConfigured()) throw new IllegalArgumentException("DATABASE_URL is required for this command.");
        try {
            URI uri = URI.create(databaseUrl);
            if (!"mysql".equals(uri.getScheme()) || uri.getHost() == null || uri.getRawUserInfo() == null ||
                !tlsQueryAccepted(uri.getRawQuery()) || uri.getFragment() != null ||
                (!uri.getPath().matches("/[A-Za-z0-9_]{1,64}") && !(!databaseSchema.isEmpty() && Set.of("", "/").contains(uri.getPath())))) {
                fail("DATABASE_URL must be a MySQL connection URL with only supported TLS options.");
            }
            String[] credentials = uri.getRawUserInfo().split(":", 2);
            if (credentials.length != 2 || credentials[0].isBlank()) fail("DATABASE_URL requires a username and password.");
            boolean local = Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(uri.getHost());
            String tls = mysqlTls.isBlank() ? (local && !production() && uri.getRawQuery() == null ? "disabled" : "required") : mysqlTls;
            if (!Set.of("required", "disabled").contains(tls) || ((!local || production() || uri.getRawQuery() != null) && !tls.equals("required"))) {
                fail("Remote and production MySQL connections require MYSQL_TLS=required with verified TLS.");
            }
            int port = uri.getPort() < 0 ? 3306 : uri.getPort();
            if (port < 1 || port > 65535) fail("MySQL port is outside its valid range.");
            String schema = databaseSchema.isEmpty() ? uri.getPath().substring(1) : databaseSchema;
            return new Database(uri.getHost(), port, decode(credentials[0]), decode(credentials[1]), schema, tls.equals("required"));
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException("Invalid MySQL configuration. Check DATABASE_URL and verified TLS settings.");
        }
    }
    static boolean tlsQueryAccepted(String query) {
        if (query == null) return true;
        if (query.length() > 100 || query.contains("&")) return false;
        String[] pair = query.split("=", -1);
        if (pair.length != 2) return false;
        try {
            String name = decode(pair[0]).toLowerCase(java.util.Locale.ROOT);
            String value = decode(pair[1]).toUpperCase(java.util.Locale.ROOT).replace('-', '_');
            return Set.of("ssl-mode", "sslmode").contains(name) && Set.of("REQUIRED", "VERIFY_CA", "VERIFY_IDENTITY").contains(value);
        } catch (IllegalArgumentException error) { return false; }
    }
    private static String decode(String value) { return URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8); }
    private static void fail(String message) { throw new IllegalArgumentException(message); }
    @Override public String toString() { return "FutureCode settings (credentials redacted)"; }

    public record Database(String host, int port, String user, String password, String schema, boolean tls) {
        @Override public String toString() { return "MySQL configuration (credentials redacted)"; }
    }
}
