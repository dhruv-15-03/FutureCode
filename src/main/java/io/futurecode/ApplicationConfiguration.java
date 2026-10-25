package io.futurecode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ApplicationConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(ApplicationConfiguration.class);
    @Bean public BoundedLimiter limiter() { return new BoundedLimiter(); }
    @Bean public Authentication authentication(AppSettings settings, ObjectProvider<Store> store, BoundedHttp http) {
        return new GoogleAuthentication(settings, store.getIfAvailable(), http);
    }
    @Bean public DatabaseSetup databaseSetup(AppSettings settings, CuratedContent content) { return new DatabaseSetup(settings, content); }
    @Bean public ApplicationRunner startup(AppSettings settings, ObjectProvider<Store> store, ObjectProvider<DatabaseMigrations> migrations, DatabaseSetup setup) {
        return args -> {
            if (settings.command().equals("provision")) { setup.run(); return; }
            if (!settings.command().equals("serve")) {
                if (migrations.getIfAvailable() == null) throw new IllegalArgumentException("DATABASE_URL is required for database commands.");
                if (settings.command().equals("migrate")) {
                    migrations.getObject().migrate();
                    LOG.info("FutureCode database migrations are current.");
                } else {
                    if (!Json.uuid(settings.adminUserId())) throw new IllegalArgumentException("Supply an existing account UUID using --futurecode.admin-user-id.");
                    migrations.getObject().verify(); store.getObject().grantAdmin(settings.adminUserId());
                    LOG.info("Moderator role granted to the requested existing account.");
                }
            } else {
                if (migrations.getIfAvailable() != null) { migrations.getObject().verify(); store.getObject().cleanSessions(); }
                LOG.info("FutureCode is ready at {}{}", settings.origin(), settings.authConfigured() ? "" : " (read-only preview: account setup required)");
            }
        };
    }
}
