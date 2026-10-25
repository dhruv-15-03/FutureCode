package io.futurecode;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.zip.ZipInputStream;
import java.io.ByteArrayInputStream;
import static org.junit.jupiter.api.Assertions.*;

class IntegrationBoundaryTest {
    private static final String KEY = "synthetic-user-key-not-live";
    private static MockHttpServletRequest webhookRequest(byte[] raw) {
        var request = new MockHttpServletRequest("POST", "/api/payments/webhook");
        request.setLocalPort(4317); request.addHeader("Host", "127.0.0.1:4317");
        request.setContentType("application/json; charset=UTF-8"); request.setContent(raw);
        return request;
    }
    @Test void webhookBoundaryPreservesSignedBytesAndDoesNotBypassOriginForOtherWrites() throws Exception {
        byte[] raw = "{\n \"event\": \"payment_link.paid\"\n}\n".getBytes(StandardCharsets.UTF_8);
        var boundary = new RequestBoundary(TestData.settings(false)); var response = new MockHttpServletResponse();
        boolean[] reached = {false};
        boundary.doFilter(webhookRequest(raw), response, (request, result) -> {
            reached[0] = true; assertArrayEquals(raw, (byte[]) request.getAttribute(RequestBoundary.RAW_BODY));
            assertEquals("payment_link.paid", Json.text((JsonNode) request.getAttribute(RequestBoundary.BODY), "event"));
        });
        assertTrue(reached[0]); assertEquals(200, response.getStatus());
        for (String path : List.of("/api/payments/checkout", "/api/runs", "/api/payments/webhook/extra")) {
            var request = webhookRequest(raw); request.setRequestURI(path);
            var denied = new MockHttpServletResponse();
            boundary.doFilter(request, denied, (ignored, ignoredResponse) -> fail("Origin checks must still apply."));
            assertEquals(403, denied.getStatus());
        }
        var unsupported = webhookRequest(raw); unsupported.setContentType("application/json; charset=UTF-16");
        var denied = new MockHttpServletResponse();
        boundary.doFilter(unsupported, denied, (ignored, ignoredResponse) -> fail("Non-UTF-8 JSON must be rejected."));
        assertEquals(403, denied.getStatus());
    }
    @Test void webhookBodyLimitAcceptsExactly32KiBAndRejectsTheNextByte() throws Exception {
        var boundary = new RequestBoundary(TestData.settings(false));
        for (int bytes : List.of(32768, 32769)) {
            byte[] raw = ("{}" + " ".repeat(bytes - 2)).getBytes(StandardCharsets.UTF_8);
            var response = new MockHttpServletResponse(); boolean[] reached = {false};
            boundary.doFilter(webhookRequest(raw), response, (request, result) -> {
                reached[0] = true; assertEquals(32768, ((byte[]) request.getAttribute(RequestBoundary.RAW_BODY)).length);
            });
            assertEquals(bytes == 32768, reached[0]); assertEquals(bytes == 32768 ? 200 : 413, response.getStatus());
        }
    }
    private static final class Transport extends BoundedHttp {
        int calls; URI destination; JsonNode sent;
        Function<JsonNode, JsonNode> reply = input -> Json.obj();
        int status = 200;
        @Override public Response request(URI uri, Map<String, String> headers, byte[] body, int max, Duration timeout) {
            calls++; destination = uri; sent = body == null ? Json.obj() : Json.parse(body);
            return new Response(status, Json.bytes(reply.apply(sent)));
        }
    }
    private static final class Fixture {
        final AppSettings settings = TestData.settings(false);
        final IntegrationSettings integrations = new IntegrationSettings(true, true, "rzp_test_fixture123", "synthetic-merchant-secret", "synthetic-webhook-secret-for-tests-only-12345", 500);
        final MemoryStore store = new MemoryStore(settings);
        final FixtureConfiguration.FixtureAuthentication auth = new FixtureConfiguration.FixtureAuthentication(store);
        final StaticListableBeanFactory beans = new StaticListableBeanFactory();
        final BoundedLimiter limiter = new BoundedLimiter();
        final CuratedContent content;
        final PlatformController platform;
        Fixture() throws Exception {
            content = new CuratedContent(); beans.addBean("store", store);
            platform = new PlatformController(settings, beans.getBeanProvider(Store.class), auth, new GradingService(content), content, limiter);
        }
        MockHttpServletRequest actor() {
            var request = new MockHttpServletRequest();
            auth.bootstrap(request, new MockHttpServletResponse()); request.addHeader("X-CSRF-Token", auth.resolve(request).csrfToken());
            return request;
        }
        MockHttpServletRequest body(MockHttpServletRequest actor, JsonNode body) {
            actor.setAttribute(RequestBoundary.BODY, body); return actor;
        }
        ExecutionController runner(Transport http) throws Exception { return new ExecutionController(integrations, platform, beans.getBeanProvider(Store.class), http, limiter, content); }
        PaymentController payments(Transport http) { return new PaymentController(integrations, settings, platform, beans.getBeanProvider(Store.class), http, limiter); }
    }
    @Test void geminiUsesItsOwnFixedDestinationAndStructuredOutputWithoutStoringOrLeakingKeys() throws Exception {
        try (Transport http = new Transport()) {
            http.reply = input -> Json.obj("choices", List.of(Json.obj("finish_reason", "stop", "message", Json.obj("content", Json.stringify(TestData.samples().path("exercise"))))));
            JsonNode result = new AiService(new CuratedContent(), http).exercise(Json.obj("source", TestData.samples().path("source"), "provider", "gemini", "key", KEY, "consent", true));
            assertEquals(3, result.path("questions").size()); assertEquals(BoundedHttp.GEMINI, http.destination);
            assertEquals("gemini-3.8-flash", Json.text(http.sent, "model")); assertEquals("low", Json.text(http.sent, "reasoning_effort"));
            assertFalse(Json.stringify(http.sent).contains(KEY));
            assertThrows(ApiError.class, () -> new AiService(new CuratedContent(), http).exercise(Json.obj("source", "x", "provider", "https://evil.test", "key", KEY, "consent", true)));
            assertEquals(1, http.calls);
        }
    }
    @Test void runnerSendsOnlyBoundedNetworkDisabledCodeToTheAllowlistedSandboxAndKeepsRecordsPrivate() throws Exception {
        Fixture f = new Fixture(); var actor = f.actor(); var other = f.actor();
        try (Transport http = new Transport()) {
            String token = UUID.randomUUID().toString(); http.reply = input -> Json.obj("token", token);
            var controller = f.runner(http);
            JsonNode input = Json.obj("kind", "snippet", "language", "python", "source", "print('hello')", "stdin", "", "expectedOutput", "", "key", KEY, "consent", true);
            JsonNode run = controller.create(f.body(actor, input)); String id = Json.text(run, "id");
            assertEquals(BoundedHttp.RUNNER, http.destination); assertEquals(71, http.sent.path("language_id").intValue());
            assertFalse(http.sent.path("enable_network").booleanValue()); assertEquals(3, http.sent.path("cpu_time_limit").intValue());
            assertFalse(Json.stringify(f.store.runs).contains(KEY)); assertFalse(Json.stringify(run).contains(token)); assertFalse(Json.stringify(f.store.runs).contains("print("));
            assertEquals(404, assertThrows(ApiError.class, () -> controller.record(id, other)).status());
            assertEquals(409, assertThrows(ApiError.class, () -> controller.create(f.body(actor, input))).status());
            http.reply = ignored -> Json.obj("status", Json.obj("id", 3, "description", "Accepted"), "stdout", Base64.getEncoder().encodeToString("hello\n".getBytes(StandardCharsets.UTF_8)),
                "stderr", null, "compile_output", null, "time", "0.01", "memory", 1024);
            JsonNode done = controller.poll(id, f.body(actor, Json.obj("key", KEY, "consent", true)));
            assertEquals("completed", Json.text(done, "state")); assertTrue(done.path("result").path("passedExpectedOutput").isNull());
            assertEquals("hello\n", Json.text(done.path("result"), "stdout")); assertTrue(f.store.attempts.isEmpty());
            int calls = http.calls; controller.poll(id, f.body(actor, Json.obj("key", KEY, "consent", true))); assertEquals(calls, http.calls);
        }
    }
    @Test void trustedRegressionPackagingCannotAcceptClientTestsOrArbitraryZipEntries() throws Exception {
        Fixture f = new Fixture(); var actor = f.actor();
        try (Transport http = new Transport()) {
            http.reply = input -> Json.obj("token", UUID.randomUUID().toString()); var controller = f.runner(http);
            JsonNode source = f.content.practiceCase().path("reference").path("counterSource");
            controller.create(f.body(actor, Json.obj("kind", "counter-regression", "language", "java", "source", source, "stdin", "", "expectedOutput", "", "key", KEY, "consent", true)));
            assertTrue(new String(Base64.getDecoder().decode(Json.text(http.sent, "source_code")), StandardCharsets.UTF_8).contains("ReadBarrierMap"));
            var names = new java.util.HashSet<String>();
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(Json.text(http.sent, "additional_files"))))) {
                for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) names.add(entry.getName());
            }
            assertEquals(java.util.Set.of("RequestCounter.java", "RequestController.java"), names);
            assertFalse(Json.stringify(http.sent).contains(KEY));
            var forged = Json.obj("kind", "counter-regression", "language", "java", "source", source, "stdin", "", "expectedOutput", "forged", "key", KEY, "consent", true);
            assertEquals(400, assertThrows(ApiError.class, () -> controller.create(f.body(actor, forged))).status());
        }
    }
    @Test void disabledIntegrationsFailClosedBeforeAnyExternalCall() throws Exception {
        Fixture f = new Fixture(); var disabled = new IntegrationSettings(false, false, "", "", "", 0);
        try (Transport http = new Transport()) {
            var runner = new ExecutionController(disabled, f.platform, f.beans.getBeanProvider(Store.class), http, f.limiter, f.content);
            var payments = new PaymentController(disabled, f.settings, f.platform, f.beans.getBeanProvider(Store.class), http, f.limiter);
            assertEquals(503, assertThrows(ApiError.class, () -> runner.create(f.actor())).status());
            assertEquals(503, assertThrows(ApiError.class, () -> payments.checkout(f.actor())).status()); assertEquals(0, http.calls);
        }
    }
    @Test void checkoutAmountsAreServerOwnedIdempotentAndOnlyASignedMatchingWebhookCanMarkPaid() throws Exception {
        Fixture f = new Fixture(); var actor = f.actor(); String requestId = UUID.randomUUID().toString();
        try (Transport http = new Transport()) {
            http.reply = input -> Json.obj("id", "plink_fixture123", "reference_id", input.path("reference_id"), "amount", 500, "currency", "INR", "short_url", "https://rzp.io/i/fixture");
            var controller = f.payments(http);
            assertEquals(400, assertThrows(ApiError.class, () -> controller.checkout(f.body(actor, Json.obj("requestId", requestId, "amount", 1)))).status());
            JsonNode invoice = controller.checkout(f.body(actor, Json.obj("requestId", requestId)));
            String id = Json.text(invoice, "id"); assertEquals(500, invoice.path("amountPaise").intValue()); assertEquals(BoundedHttp.RAZORPAY, http.destination);
            assertFalse(Json.stringify(http.sent).contains("fixture@example")); assertFalse(Json.stringify(http.sent).contains("synthetic-merchant-secret"));
            controller.checkout(f.body(actor, Json.obj("requestId", requestId))); assertEquals(1, http.calls);
            JsonNode event = Json.obj("event", "payment_link.paid", "payload", Json.obj("payment_link", Json.obj("entity", Json.obj("id", "plink_fixture123",
                "reference_id", id, "amount", 500, "amount_paid", 500, "currency", "INR", "status", "paid"))));
            byte[] raw = Json.bytes(event); var request = new MockHttpServletRequest(); request.setAttribute(RequestBoundary.RAW_BODY, raw); request.setAttribute(RequestBoundary.BODY, event);
            request.addHeader("X-Razorpay-Event-Id", "event_fixture123"); request.addHeader("X-Razorpay-Signature", "0".repeat(64));
            assertEquals(401, assertThrows(ApiError.class, () -> controller.webhook(request)).status());
            request.removeHeader("X-Razorpay-Signature"); request.addHeader("X-Razorpay-Signature", Crypto.hmacHex(f.integrations.razorpayWebhookSecret(), raw));
            assertTrue(controller.webhook(request).path("applied").booleanValue()); assertFalse(controller.webhook(request).path("applied").booleanValue());
            assertEquals("paid", Json.text(controller.history(actor).path("items").get(0), "state"));
            assertThrows(ApiError.class, () -> f.store.applyPaymentEvent("event_other123", "payment_link.paid", id, "plink_fixture123", 1, "INR", "paid"));
            f.store.applyPaymentEvent("event_expired123", "payment_link.expired", id, "plink_fixture123", 500, "INR", "expired");
            assertEquals("paid", Json.text(controller.history(actor).path("items").get(0), "state"));
            assertFalse(PaymentController.validCheckout("https://evil.test/checkout")); assertFalse(PaymentController.validCheckout("javascript:alert(1)"));
        }
    }
    @Test void patchAdviceStaysUnverifiedAndRejectsForgedRunEvidence() throws Exception {
        Fixture f = new Fixture(); var actor = f.actor();
        try (Transport http = new Transport()) {
            JsonNode advisory = Json.obj("summary", "Inspect atomicity.", "assumptions", List.of("One shared counter."), "risks", List.of("Public tests are not exhaustive."),
                "suggestedChecks", List.of("Force two reads before writes."), "citations", List.of(Json.obj("file", "CandidatePatch.java", "line", 1, "quote", "candidate text")));
            http.reply = input -> Json.obj("choices", List.of(Json.obj("finish_reason", "stop", "message", Json.obj("content", Json.stringify(advisory)))));
            var review = new PatchReviewController(f.platform, f.beans.getBeanProvider(Store.class), new AiService(f.content, http), f.content, new ProviderGate(f.settings));
            JsonNode input = Json.obj("caseId", "missing-requests-v1", "patch", "candidate text", "reasoning", "Explain intended invariants.", "runId", null, "key", KEY, "consent", true);
            JsonNode result = review.review(f.body(actor, input));
            assertEquals("ai-advisory-unverified", Json.text(result, "verification")); assertFalse(result.has("score")); assertTrue(f.store.attempts.isEmpty());
            ((ObjectNode) input).put("runId", UUID.randomUUID().toString());
            assertEquals(404, assertThrows(ApiError.class, () -> review.review(f.body(actor, input))).status());
        }
    }
    @Test void runnerAndCheckoutDestinationsRejectUrlInjection() {
        for (String uri : List.of("http://judge0-ce.p.rapidapi.com/submissions", "https://judge0-ce.p.rapidapi.com.evil.test/submissions",
            "https://user@judge0-ce.p.rapidapi.com/submissions", "https://judge0-ce.p.rapidapi.com/submissions/../../admin",
            "https://judge0-ce.p.rapidapi.com/submissions?callback_url=https://evil.test")) assertFalse(BoundedHttp.allowed(URI.create(uri)));
    }
}
