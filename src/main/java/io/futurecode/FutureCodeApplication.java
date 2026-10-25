package io.futurecode;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import java.util.Arrays;

@SpringBootApplication(exclude = org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration.class)
@EnableConfigurationProperties({AppSettings.class, IntegrationSettings.class})
public class FutureCodeApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(FutureCodeApplication.class);
        boolean provision = Arrays.asList(args).contains("--futurecode.command=provision");
        if (provision) {
            var privateArgs = new java.util.ArrayList<>(Arrays.asList(args));
            for (String logger : new String[]{"root", "io.futurecode", "com.zaxxer.hikari", "org.flywaydb"}) privateArgs.add("--logging.level." + logger + "=OFF");
            privateArgs.add("--spring.main.log-startup-info=false");
            args = privateArgs.toArray(String[]::new);
        }
        if (Arrays.stream(args).anyMatch(value -> value.startsWith("--futurecode.command=") && !value.equals("--futurecode.command=serve"))) {
            app.setWebApplicationType(WebApplicationType.NONE);
            try (var context = app.run(args)) {
                System.exit(SpringApplication.exit(context));
            } catch (RuntimeException error) {
                if (provision) System.out.println(DatabaseSetup.REPORT_PREFIX + Json.stringify(Json.obj("ok", false, "code", "configuration_invalid")));
                else System.err.println("FutureCode database command failed. Check private configuration and schema readiness; no credentials were displayed.");
                System.exit(1);
            }
        } else {
            try { app.run(args); }
            catch (RuntimeException error) {
                System.err.println("FutureCode could not start. Check private configuration and database readiness.");
                System.exit(1);
            }
        }
    }
}
