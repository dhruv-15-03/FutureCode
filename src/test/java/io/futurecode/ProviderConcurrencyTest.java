package io.futurecode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class ProviderConcurrencyTest {
    @Test void permitsExactlyTheGlobalLimitAndOnePaidRequestPerAccountAndReleasesBoth() throws Exception {
        ObjectNode config = (ObjectNode) Json.node(TestData.settings(false)); config.put("providerConcurrency", 2);
        AppSettings settings = Json.MAPPER.treeToValue(config, AppSettings.class);
        CuratedContent content = new CuratedContent(); MemoryStore store = new MemoryStore(settings);
        var auth = new FixtureConfiguration.FixtureAuthentication(store);
        var beans = new StaticListableBeanFactory(); beans.addBean("store", store);
        PlatformController platform = new PlatformController(settings, beans.getBeanProvider(Store.class), auth, new GradingService(content), content, new BoundedLimiter());
        CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
        try (BoundedHttp transport = new BoundedHttp() {
            @Override public Response request(java.net.URI uri, Map<String, String> headers, byte[] body, int max, java.time.Duration timeout) {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Provider test was not released."); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
                return new Response(200, Json.bytes(Json.obj("choices", List.of(Json.obj("finish_reason", "stop", "message", Json.obj("content", Json.stringify(TestData.samples().path("exercise"))))))));
            }
        }; var workers = Executors.newFixedThreadPool(2)) {
            AiController controller = new AiController(new ProviderGate(settings), platform, new AiService(content, transport));
            MockHttpServletRequest[] requests = new MockHttpServletRequest[3];
            for (int index = 0; index < requests.length; index++) {
                var request = new MockHttpServletRequest("POST", "/api/exercise"); auth.bootstrap(request, new MockHttpServletResponse());
                request.addHeader("X-CSRF-Token", auth.resolve(request).csrfToken());
                request.setAttribute(RequestBoundary.BODY, Json.obj("source", TestData.samples().path("source"), "key", "sk-synthetic-test-only", "consent", true)); requests[index] = request;
            }
            var first = workers.submit(() -> controller.generate(requests[0]));
            var second = workers.submit(() -> controller.generate(requests[1]));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertEquals(429, assertThrows(ApiError.class, () -> controller.generate(requests[0])).status());
                assertEquals(429, assertThrows(ApiError.class, () -> controller.generate(requests[2])).status());
            } finally { release.countDown(); }
            assertEquals(3, first.get(5, TimeUnit.SECONDS).path("questions").size()); assertEquals(3, second.get(5, TimeUnit.SECONDS).path("questions").size());
            assertEquals(3, controller.generate(requests[2]).path("questions").size());
        }
    }
}
