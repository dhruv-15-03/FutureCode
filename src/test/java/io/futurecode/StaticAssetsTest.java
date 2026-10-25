package io.futurecode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.node.ObjectNode;
import java.util.zip.GZIPInputStream;
import java.io.ByteArrayInputStream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StaticAssetsTest {
    @Test void productionAssetsUseGzipWeakValidatorsAndRespectRefusedEncodings() throws Exception {
        ObjectNode config = (ObjectNode) Json.node(TestData.settings(true));
        config.put("environment", "production"); config.put("origin", "https://futurecode.example"); config.put("bindAddress", "0.0.0.0"); config.put("mysqlTls", "required");
        AppSettings settings = Json.MAPPER.treeToValue(config, AppSettings.class);
        MemoryStore store = new MemoryStore(settings); var beans = new StaticListableBeanFactory(); beans.addBean("store", store);
        PublicController controller = new PublicController(settings, new FixtureConfiguration.FixtureAuthentication(store), beans.getBeanProvider(Store.class), mock(ApplicationAvailability.class));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/styles.css"); request.addHeader("Accept-Encoding", "br;q=0, gzip;q=1");
        var compressed = controller.asset(request);
        assertEquals("gzip", compressed.getHeaders().getFirst("Content-Encoding")); assertTrue(compressed.getHeaders().getETag().startsWith("W/\""));
        try (var input = new GZIPInputStream(new ByteArrayInputStream(compressed.getBody()))) {
            String css = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(css.contains("color-scheme: dark"));
            assertTrue(css.contains("--canvas: #161210"));
        }
        request.addHeader("If-None-Match", compressed.getHeaders().getETag()); assertEquals(304, controller.asset(request).getStatusCode().value());
        request = new MockHttpServletRequest("GET", "/app.js"); request.addHeader("Accept-Encoding", "br;q=0, gzip;q=0");
        assertNull(controller.asset(request).getHeaders().getFirst("Content-Encoding"));
        var unacceptable = new MockHttpServletRequest("GET", "/app.js"); unacceptable.addHeader("Accept-Encoding", "gzip;q=0, identity;q=0");
        assertEquals(406, assertThrows(ApiError.class, () -> controller.asset(unacceptable)).status());
    }
    @Test void unicodeWhitespaceUsesTheSameTrimmingContractAsBrowserJavaScript() {
        assertEquals("source();", Json.trim("\ufeff\u00a0 source();\u00a0"));
        assertFalse(Json.bounded("\u00a0", 10, true));
    }
    @Test void readinessWaitsForAllStartupRunnersInsteadOfOnlyAnOpenHttpPort() throws Exception {
        AppSettings settings = TestData.settings(false);
        MemoryStore store = new MemoryStore(settings); var beans = new StaticListableBeanFactory(); beans.addBean("store", store);
        ApplicationAvailability availability = mock(ApplicationAvailability.class);
        PublicController controller = new PublicController(settings, new FixtureConfiguration.FixtureAuthentication(store), beans.getBeanProvider(Store.class), availability);
        when(availability.getReadinessState()).thenReturn(ReadinessState.REFUSING_TRAFFIC);
        assertEquals("not_ready", assertThrows(ApiError.class, controller::ready).code());
        assertEquals("ok", Json.text(controller.live(), "status"));
        when(availability.getReadinessState()).thenReturn(ReadinessState.ACCEPTING_TRAFFIC);
        assertEquals("ready", Json.text(controller.ready(), "status"));
    }
}
