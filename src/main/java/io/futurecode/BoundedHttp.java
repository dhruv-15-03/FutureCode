package io.futurecode;

import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class BoundedHttp implements AutoCloseable {
    public static final URI OPENAI = URI.create("https://api.openai.com/v1/chat/completions");
    public static final URI GEMINI = URI.create("https://generativelanguage.googleapis.com/v1beta/openai/chat/completions");
    public static final URI TOKEN = URI.create("https://oauth2.googleapis.com/token");
    public static final URI JWKS = URI.create("https://www.googleapis.com/oauth2/v3/certs");
    public static final URI RAZORPAY = URI.create("https://api.razorpay.com/v1/payment_links");
    public static final URI RUNNER = URI.create("https://judge0-ce.p.rapidapi.com/submissions?base64_encoded=true&wait=false");
    private static final Set<URI> ALLOWED = Set.of(OPENAI, GEMINI, TOKEN, JWKS, RAZORPAY, RUNNER);
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 8, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(32), Thread.ofPlatform().daemon(true).name("provider-http-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER).executor(executor).build();
    private final Semaphore capacity = new Semaphore(12);

    public record Response(int status, byte[] body) {}
    public static final class Failure extends RuntimeException {
        public enum Kind { TIMEOUT, TOO_LARGE, NETWORK, BUSY }
        private final Kind kind;
        public Failure(Kind kind) { super("Upstream request failed: " + kind); this.kind = kind; }
        public Kind kind() { return kind; }
    }
    public Response request(URI destination, Map<String, String> headers, byte[] body, int maxBytes, Duration timeout) {
        if (!allowed(destination)) throw new IllegalArgumentException("The outbound destination is not allowlisted.");
        if (!capacity.tryAcquire()) throw new Failure(Failure.Kind.BUSY);
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(destination).timeout(timeout);
            headers.forEach(request::header);
            if (body == null) request.GET(); else request.POST(HttpRequest.BodyPublishers.ofByteArray(body));
            pending = client.sendAsync(request.build(), info -> new LimitedBody(maxBytes));
            HttpResponse<byte[]> response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return new Response(response.statusCode(), response.body());
        } catch (TimeoutException error) {
            throw new Failure(Failure.Kind.TIMEOUT);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new Failure(Failure.Kind.NETWORK);
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof Failure failure) throw failure;
            if (cause instanceof java.net.http.HttpTimeoutException) throw new Failure(Failure.Kind.TIMEOUT);
            throw new Failure(Failure.Kind.NETWORK);
        } catch (java.util.concurrent.RejectedExecutionException error) {
            throw new Failure(Failure.Kind.BUSY);
        } finally {
            if (pending != null && !pending.isDone()) pending.cancel(true);
            capacity.release();
        }
    }
    static boolean allowed(URI uri) {
        if (ALLOWED.contains(uri)) return true;
        return uri.getUserInfo() == null && uri.getPort() == -1 && uri.getFragment() == null && "https".equals(uri.getScheme()) &&
            "judge0-ce.p.rapidapi.com".equals(uri.getHost()) && uri.getPath().startsWith("/submissions/") &&
            Json.uuid(uri.getPath().substring("/submissions/".length())) &&
            "base64_encoded=true&fields=status,stdout,stderr,compile_output,time,memory".equals(uri.getRawQuery());
    }
    @PreDestroy @Override public void close() { client.close(); executor.shutdownNow(); }

    static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int max;
        private Flow.Subscription subscription;
        LimitedBody(int max) { this.max = max; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> chunks) {
            for (ByteBuffer chunk : chunks) {
                if (chunk.remaining() > max - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new Failure(Failure.Kind.TOO_LARGE));
                    return;
                }
                byte[] part = new byte[chunk.remaining()];
                chunk.get(part); bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
