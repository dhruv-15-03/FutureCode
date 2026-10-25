package io.futurecode;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.util.Properties;

@Configuration(proxyBeanMethods = false)
@Conditional(PersistenceConfiguration.DatabaseConfigured.class)
public class PersistenceConfiguration {
    static final class DatabaseConfigured implements Condition {
        @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return !context.getEnvironment().getProperty("futurecode.database-url", "").isBlank() &&
                !context.getEnvironment().getProperty("futurecode.command", "serve").equals("provision");
        }
    }
    @Bean(destroyMethod = "close") public DatabasePool databasePool(AppSettings settings) { return new DatabasePool(settings); }
    @Bean public JdbcTemplate jdbcTemplate(DatabasePool pool) { return new JdbcTemplate(pool.source); }
    @Bean public DatabaseMigrations databaseMigrations(DatabasePool pool, JdbcTemplate jdbc, AppSettings settings) { return new DatabaseMigrations(pool.source, jdbc, settings); }
    @Bean public Store store(DatabasePool pool, JdbcTemplate jdbc, AppSettings settings) {
        return new JdbcStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(pool.source)), settings);
    }

    static final class DatabasePool implements AutoCloseable {
        final HikariDataSource source;
        private Path generatedTrust;
        DatabasePool(AppSettings settings) {
            this(settings, settings.database().schema(), false);
        }
        DatabasePool(AppSettings settings, String schema, boolean lazy) {
            AppSettings.Database database = settings.database();
            if (!schema.isEmpty() && !schema.matches("[A-Za-z0-9_]{1,64}")) throw new IllegalArgumentException("Invalid database schema.");
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl("jdbc:mysql://" + database.host() + ":" + database.port() + "/" + schema);
            if (lazy) config.setInitializationFailTimeout(-1);
            config.setUsername(database.user()); config.setPassword(database.password());
            config.setMaximumPoolSize(settings.connectionLimit()); config.setMinimumIdle(0);
            config.setConnectionTimeout(8000); config.setValidationTimeout(3000); config.setIdleTimeout(30000); config.setMaxLifetime(900000);
            config.setPoolName("futurecode-mysql"); config.setConnectionInitSql("SET time_zone = '+00:00'");
            Properties properties = new Properties();
            properties.setProperty("connectTimeout", "8000"); properties.setProperty("socketTimeout", "8000");
            properties.setProperty("connectionTimeZone", "UTC"); properties.setProperty("forceConnectionTimeZoneToSession", "true");
            properties.setProperty("preserveInstants", "true"); properties.setProperty("characterEncoding", "UTF-8");
            properties.setProperty("allowMultiQueries", "false"); properties.setProperty("allowLoadLocalInfile", "false");
            properties.setProperty("cachePrepStmts", "true"); properties.setProperty("prepStmtCacheSize", "64");
            properties.setProperty("sslMode", database.tls() ? "VERIFY_IDENTITY" : "DISABLED");
            if (!settings.mysqlCaFile().isBlank()) configureCa(settings.mysqlCaFile(), properties);
            config.setDataSourceProperties(properties);
            try { source = new HikariDataSource(config); }
            catch (RuntimeException error) {
                deleteTrust();
                throw new IllegalStateException("MySQL could not initialize. Check connectivity and verified TLS; credentials were not logged.");
            }
        }
        private void configureCa(String file, Properties properties) {
            try (var input = Files.newInputStream(Path.of(file))) {
                var certificates = CertificateFactory.getInstance("X.509").generateCertificates(input);
                if (certificates.isEmpty() || certificates.size() > 16) throw new IllegalArgumentException("Invalid certificate bundle.");
                KeyStore trust = KeyStore.getInstance("PKCS12"); trust.load(null);
                int index = 0;
                for (var certificate : certificates) trust.setCertificateEntry("ca-" + index++, certificate);
                String password = Crypto.random();
                generatedTrust = Files.createTempFile("futurecode-mysql-ca-", ".p12");
                try (var output = Files.newOutputStream(generatedTrust)) { trust.store(output, password.toCharArray()); }
                properties.setProperty("trustCertificateKeyStoreUrl", generatedTrust.toUri().toString());
                properties.setProperty("trustCertificateKeyStoreType", "PKCS12");
                properties.setProperty("trustCertificateKeyStorePassword", password);
                properties.setProperty("fallbackToSystemTrustStore", "false");
            } catch (java.io.IOException | java.security.GeneralSecurityException | IllegalArgumentException error) {
                deleteTrust();
                throw new IllegalStateException("MYSQL_CA_FILE is not a valid readable X.509 CA bundle.");
            }
        }
        private void deleteTrust() {
            if (generatedTrust != null) try { Files.deleteIfExists(generatedTrust); }
            catch (java.io.IOException error) { org.slf4j.LoggerFactory.getLogger(PersistenceConfiguration.class).warn("A generated public-CA trust store could not be removed."); }
        }
        @Override public void close() { source.close(); deleteTrust(); }
    }
}
