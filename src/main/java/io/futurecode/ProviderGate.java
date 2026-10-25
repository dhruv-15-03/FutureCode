package io.futurecode;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

@Component
public final class ProviderGate {
    private final Semaphore capacity;
    private final Set<String> activeUsers = ConcurrentHashMap.newKeySet();
    public ProviderGate(AppSettings settings) { capacity = new Semaphore(settings.providerConcurrency()); }
    public JsonNode execute(String user, Supplier<JsonNode> work) {
        if (!activeUsers.add(user)) throw busy();
        if (!capacity.tryAcquire()) { activeUsers.remove(user); throw busy(); }
        try { return work.get(); }
        finally { capacity.release(); activeUsers.remove(user); }
    }
    private static ApiError busy() { return new ApiError(429, "AI request capacity is busy, or your account already has an active request. Retry explicitly after it finishes.", "provider_busy"); }
}
