package io.futurecode;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("futurecode.integrations")
public record IntegrationSettings(boolean executionEnabled, boolean paymentsEnabled, String razorpayKeyId,
    String razorpayKeySecret, String razorpayWebhookSecret, int supportAmountPaise) {
    public IntegrationSettings {
        if (razorpayKeyId == null) razorpayKeyId = "";
        if (razorpayKeySecret == null) razorpayKeySecret = "";
        if (razorpayWebhookSecret == null) razorpayWebhookSecret = "";
        if (paymentsEnabled && (!razorpayKeyId.matches("rzp_(test|live)_[A-Za-z0-9]{6,80}") ||
            !razorpayKeySecret.matches("[!-~]{10,256}") || !razorpayWebhookSecret.matches("[!-~]{32,256}") ||
            supportAmountPaise < 100 || supportAmountPaise > 1000000)) {
            throw new IllegalArgumentException("Payments require complete Razorpay credentials, a random webhook secret and an explicitly configured INR support amount.");
        }
    }
    @Override public String toString() { return "Integration settings (secrets redacted)"; }
}
