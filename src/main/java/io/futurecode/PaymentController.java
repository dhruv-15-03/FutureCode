package io.futurecode;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

@RestController
public final class PaymentController {
    private final IntegrationSettings integrations;
    private final AppSettings settings;
    private final PlatformController platform;
    private final Store store;
    private final BoundedHttp http;
    private final BoundedLimiter limiter;
    public PaymentController(IntegrationSettings integrations, AppSettings settings, PlatformController platform,
        ObjectProvider<Store> store, BoundedHttp http, BoundedLimiter limiter) {
        this.integrations = integrations; this.settings = settings; this.platform = platform; this.store = store.getIfAvailable(); this.http = http; this.limiter = limiter;
    }
    private void ready() {
        if (!integrations.paymentsEnabled() || store == null) throw new ApiError(503, "Payments are not enabled. The beta remains free; no checkout was created.", "payments_unavailable");
    }
    @GetMapping("/api/payments/config") public JsonNode config() {
        return Json.obj("enabled", integrations.paymentsEnabled() && store != null, "currency", "INR",
            "amountPaise", integrations.paymentsEnabled() ? integrations.supportAmountPaise() : null,
            "notice", "Optional support payment only. Practice is free. Hosted Razorpay checkout; payment fees and policies apply.");
    }
    @GetMapping("/api/payments") public JsonNode history(HttpServletRequest request) {
        var current = platform.requireAccount(request, false, false); return Json.obj("items", store.listPayments(current.user().id()));
    }
    @PostMapping("/api/payments/checkout") public JsonNode checkout(HttpServletRequest request) {
        ready(); var current = platform.requireAccount(request, true, false);
        limiter.consume("payment:" + current.user().id(), 5, 3600000);
        Object supplied = request.getAttribute(RequestBoundary.BODY);
        if (!(supplied instanceof JsonNode input) || !Json.exact(input, "requestId") || !Json.uuid(Json.text(input, "requestId"))) throw ApiError.bad("Use an explicit checkout request identifier. Prices and currency are assigned by the server.");
        ObjectNode invoice = store.reservePayment(current.user().id(), Json.text(input, "requestId"), integrations.supportAmountPaise());
        if (!invoice.path("newlyCreated").booleanValue()) return publicInvoice(invoice);
        String id = Json.text(invoice, "id");
        try {
            String authorization = "Basic " + Base64.getEncoder().encodeToString((integrations.razorpayKeyId() + ":" + integrations.razorpayKeySecret()).getBytes(StandardCharsets.UTF_8));
            JsonNode body = Json.obj("amount", integrations.supportAmountPaise(), "currency", "INR", "accept_partial", false,
                "expire_by", Instant.parse(Json.text(invoice, "expiresAt")).getEpochSecond(), "reference_id", id, "description", "Optional support for FutureCode",
                "notify", Json.obj("sms", false, "email", false), "reminder_enable", false,
                "callback_url", settings.origin() + "/payments/return", "callback_method", "get");
            var response = http.request(BoundedHttp.RAZORPAY, Map.of("Authorization", authorization, "Content-Type", "application/json"), Json.bytes(body), 32768, Duration.ofSeconds(15));
            if (response.status() < 200 || response.status() >= 300) throw paymentError();
            JsonNode data = Json.parse(response.body()); String providerId = Json.text(data, "id"), url = Json.text(data, "short_url");
            if (!providerId.matches("plink_[A-Za-z0-9]{6,80}") || !Json.text(data, "reference_id").equals(id) ||
                Json.integer(data, "amount", -1) != integrations.supportAmountPaise() || !Json.text(data, "currency").equals("INR") || !validCheckout(url)) throw paymentError();
            store.updatePayment(current.user().id(), id, "created", providerId, url);
            invoice.put("state", "created").put("checkoutUrl", url); return publicInvoice(invoice);
        } catch (BoundedHttp.Failure | ApiError error) {
            store.updatePayment(current.user().id(), id, "unconfirmed", null, null);
            throw paymentError();
        }
    }
    @PostMapping("/api/payments/webhook") public JsonNode webhook(HttpServletRequest request) {
        ready();
        Object raw = request.getAttribute(RequestBoundary.RAW_BODY);
        String signature = request.getHeader("X-Razorpay-Signature"), eventId = request.getHeader("X-Razorpay-Event-Id");
        if (!(raw instanceof byte[] bytes) || signature == null || !signature.matches("[a-fA-F0-9]{64}") ||
            !Crypto.equal(Crypto.hmacHex(integrations.razorpayWebhookSecret(), bytes), signature.toLowerCase(java.util.Locale.ROOT))) {
            throw new ApiError(401, "The payment webhook signature is invalid.", "webhook_signature_failed");
        }
        if (eventId == null || !eventId.matches("[A-Za-z0-9_-]{8,100}")) throw ApiError.bad("A provider event identifier is required.");
        JsonNode event = (JsonNode) request.getAttribute(RequestBoundary.BODY);
        String type = Json.text(event, "event");
        if (!Set.of("payment_link.paid", "payment_link.cancelled", "payment_link.expired").contains(type)) return Json.obj("received", true, "applied", false, "reason", "unsupported_event");
        JsonNode link = event.path("payload").path("payment_link").path("entity");
        String reference = Json.text(link, "reference_id"), providerId = Json.text(link, "id");
        int amount = Json.integer(link, "amount", -1);
        String state = type.substring("payment_link.".length());
        if (!Json.uuid(reference) || !providerId.matches("plink_[A-Za-z0-9]{6,80}") || amount < 100 || !Json.text(link, "currency").equals("INR") ||
            !Json.text(link, "status").equals(state) || (state.equals("paid") && Json.integer(link, "amount_paid", -1) != amount)) throw ApiError.bad("The provider event is incomplete or inconsistent.");
        return Json.obj("received", true, "applied", store.applyPaymentEvent(eventId, type, reference, providerId, amount, "INR", state));
    }
    static boolean validCheckout(String value) {
        try {
            URI uri = URI.create(value);
            return value.length() <= 512 && uri.getHost() != null && uri.getUserInfo() == null && uri.getPort() == -1 && "https".equals(uri.getScheme()) &&
                Set.of("rzp.io", "rzp.live", "pages.razorpay.com").contains(uri.getHost()) && uri.getFragment() == null;
        } catch (IllegalArgumentException error) { return false; }
    }
    private static JsonNode publicInvoice(JsonNode row) { return Json.fields(row, "id", "amountPaise", "currency", "state", "checkoutUrl", "expiresAt", "createdAt"); }
    private static ApiError paymentError() { return new ApiError(502, "Checkout creation was not confirmed. Check your payment history before retrying; no payment has been marked paid.", "payment_provider_error"); }
}
