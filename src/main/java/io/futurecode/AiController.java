package io.futurecode;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
public final class AiController {
    private final ProviderGate gate;
    private final PlatformController platform;
    private final AiService service;
    public AiController(ProviderGate gate, PlatformController platform, AiService service) {
        this.gate = gate; this.platform = platform; this.service = service;
    }
    @PostMapping({"/api/exercise", "/api/coach"}) public JsonNode generate(HttpServletRequest request) {
        var session = platform.providerAccount(request);
        return gate.execute(session.user().id(), () -> {
            Object value = request.getAttribute(RequestBoundary.BODY);
            if (!(value instanceof JsonNode input)) throw ApiError.bad("Request must contain valid JSON.");
            return request.getRequestURI().equals("/api/coach") ? service.coach(input) : service.exercise(input);
        });
    }
    @GetMapping("/api/ai/providers") public JsonNode providers() {
        var items = Json.arr();
        for (AiProvider provider : AiProvider.values()) items.add(Json.obj("id", provider.id(), "name", provider.label(), "model", provider.model(), "origin", provider.endpoint().getHost()));
        return Json.obj("items", items, "notice", "Keys are per request, not stored. Provider charges and data policies apply. Live integrations require separate verification.");
    }
}
