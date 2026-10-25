package io.futurecode;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

public final class FixtureApplication {
    private FixtureApplication() {}
    public static ConfigurableApplicationContext start(int port) {
        return new SpringApplicationBuilder(FutureCodeApplication.class, FixtureConfiguration.class).run(
            "--server.port=" + port, "--server.address=127.0.0.1", "--futurecode.environment=development",
            "--futurecode.origin=http://127.0.0.1:4318", "--futurecode.command=serve", "--futurecode.database-url=",
            "--futurecode.google-client-id=", "--futurecode.google-client-secret=", "--futurecode.session-secret=",
            "--futurecode.max-users=10000", "--futurecode.mysql-ca-file=",
            "--futurecode.integrations.execution-enabled=false", "--futurecode.integrations.payments-enabled=false",
            "--futurecode.integrations.razorpay-key-id=", "--futurecode.integrations.razorpay-key-secret=",
            "--futurecode.integrations.razorpay-webhook-secret=", "--futurecode.integrations.support-amount-paise=0");
    }
    public static void main(String[] args) { start(4318); }
}
