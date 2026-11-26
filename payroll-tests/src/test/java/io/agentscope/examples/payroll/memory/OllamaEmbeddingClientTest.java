package io.agentscope.examples.payroll.memory;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OllamaEmbeddingClientTest {

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/api/embed",
                exchange -> {
                    requests.incrementAndGet();
                    exchange.getRequestBody().readAllBytes();
                    byte[] body =
                            "{\"embeddings\":[[0.25,0.5]]}"
                                    .getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    exchange.getResponseBody().write(body);
                    exchange.close();
                });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    @DisplayName("短文本命中 3 秒缓存，重复问题不再发起 HTTP 请求")
    void reusesCachedVectorWithinTtl() {
        OllamaEmbeddingClient client = client();

        float[] first = client.embed("月薪怎么算");
        int afterFirst = requests.get();
        float[] second = client.embed("月薪怎么算");

        assertArrayEquals(new float[] {0.25f, 0.5f}, first, 0.0001f);
        assertArrayEquals(new float[] {0.25f, 0.5f}, second, 0.0001f);
        assertEquals(1, afterFirst);
        assertEquals(afterFirst, requests.get());
    }

    @Test
    @DisplayName("同题并发调用可并行进服务，回填后再次调用直接命中缓存")
    void concurrentSameTextSettlesOnSingleCacheEntry() throws Exception {
        OllamaEmbeddingClient client = client();
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<float[]>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return client.embed("并发问题");
                                }));
            }
            start.countDown();
            for (Future<float[]> future : futures) {
                assertArrayEquals(new float[] {0.25f, 0.5f}, future.get(), 0.0001f);
            }
            assertTrue(requests.get() >= 1 && requests.get() <= threads);

            int settled = requests.get();
            client.embed("并发问题");
            assertEquals(settled, requests.get(), "回填成功后应命中缓存");
        } finally {
            pool.shutdownNow();
        }
    }

    private OllamaEmbeddingClient client() {
        return new OllamaEmbeddingClient(
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "test-model",
                2,
                Duration.ofSeconds(2));
    }
}
