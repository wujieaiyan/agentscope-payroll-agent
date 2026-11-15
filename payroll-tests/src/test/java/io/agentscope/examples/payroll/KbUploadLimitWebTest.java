package io.agentscope.examples.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        classes = PayrollWebApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KbUploadLimitWebTest {

    private static final String ADMIN_PASSWORD = "kb-upload-admin-pass";
    private static final Path OUTPUT_DIR = tempDir();

    @DynamicPropertySource
    static void payrollProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "payroll.db.url",
                () -> "jdbc:h2:mem:kb-upload-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        registry.add("payroll.db.user", () -> "sa");
        registry.add("payroll.db.password", () -> "");
        registry.add("payroll.output-dir", () -> OUTPUT_DIR.toString());
        registry.add("payroll.auth.enabled", () -> "true");
        registry.add("payroll.auth.admin-password", () -> ADMIN_PASSWORD);
        registry.add("payroll.kb.storage-dir", () -> OUTPUT_DIR.resolve("kb").toString());
        registry.add("payroll.kb.es-enabled", () -> "false");
        registry.add("payroll.kb.embedding-base-url", () -> "http://127.0.0.1:1");
        registry.add("payroll.kb.answer-enabled", () -> "false");
        registry.add("spring.servlet.multipart.max-file-size", () -> "1MB");
        registry.add("spring.servlet.multipart.max-request-size", () -> "1MB");
        registry.add("server.tomcat.max-swallow-size", () -> "-1");
    }

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("超过 multipart 上限返回 413 与可读提示，而不是 500")
    void rejectsOversizedUpload() throws Exception {
        HttpClient client = newClient();
        login(client);

        String boundary = "----kb-upload-" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(
                ("--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"files\"; filename=\"big.md\"\r\n"
                        + "Content-Type: text/markdown; charset=UTF-8\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8));
        body.write("x".repeat(2 * 1024 * 1024).getBytes(StandardCharsets.UTF_8));
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpResponse<String> response =
                client.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                "http://127.0.0.1:"
                                                        + port
                                                        + "/api/kb/series/1/documents"))
                                .header(
                                        "Content-Type",
                                        "multipart/form-data; boundary=" + boundary)
                                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                                .build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(413, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"status\":\"ERROR\""), response.body());
        assertTrue(response.body().contains("单文件上限 1 MB"), response.body());
    }

    private void login(HttpClient client) throws Exception {
        HttpResponse<String> response =
                client.send(
                        HttpRequest.newBuilder(
                                        URI.create("http://127.0.0.1:" + port + "/api/auth/login"))
                                .header("Content-Type", "application/json")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "{\"username\":\"admin\",\"password\":\""
                                                        + ADMIN_PASSWORD
                                                        + "\"}",
                                                StandardCharsets.UTF_8))
                                .build(),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode(), response.body());
    }

    private static HttpClient newClient() {
        CookieManager cookies = new CookieManager();
        cookies.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        return HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("kb-upload-test-");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
