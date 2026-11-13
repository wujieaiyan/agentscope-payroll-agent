package io.agentscope.examples.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
        classes = PayrollWebApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApprovalWebTest {

    private static final String ADMIN_PASSWORD = "approval-admin-pass";
    private static final Path OUTPUT_DIR = tempDir();
    private static final String DB_URL =
            "jdbc:h2:mem:approval-web-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";

    @DynamicPropertySource
    static void payrollProperties(DynamicPropertyRegistry registry) {
        registry.add("payroll.db.url", () -> DB_URL);
        registry.add("payroll.db.user", () -> "sa");
        registry.add("payroll.db.password", () -> "");
        registry.add("payroll.output-dir", () -> OUTPUT_DIR.toString());
        registry.add("payroll.auth.enabled", () -> "true");
        registry.add("payroll.auth.admin-password", () -> ADMIN_PASSWORD);
    }

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("HR 提交、管理员审批后出账，提交人不能自批")
    void enforcesApprovalWorkflowAndPermissions() throws Exception {
        HttpClient admin = newClient();
        login(admin, "admin", ADMIN_PASSWORD);
        createUser(admin, "hr_approval", "hr-approval-pass", "HR");
        createUser(admin, "viewer_approval", "viewer-approval-pass", "VIEWER");

        HttpClient hr = newClient();
        login(hr, "hr_approval", "hr-approval-pass");
        long runId = preview(hr);

        assertEquals(403, getRaw(hr, "/api/approval/flows").statusCode());
        assertEquals(
                403,
                postRaw(
                                hr,
                                "/api/approval/flows",
                                "{\"name\":\"HR 自建\",\"scope_type\":\"DEPARTMENT\","
                                        + "\"scope_value\":\"研发部\"}")
                        .statusCode());

        String submitted =
                post(hr, "/api/runs/" + runId + "/submit-approval", "{\"comment\":\"月度核算\"}");
        assertTrue(submitted.contains("\"PENDING_APPROVAL\""), submitted);
        assertTrue(submitted.contains("\"workflow_steps\""), submitted);
        assertTrue(submitted.contains("\"current_step\":0"), submitted);

        String adminTasks = get(admin, "/api/approval/tasks");
        assertTrue(adminTasks.contains("\"run_id\":" + runId), adminTasks);
        String hrTasks = get(hr, "/api/approval/tasks");
        assertFalse(hrTasks.contains("\"run_id\":" + runId), hrTasks);

        HttpResponse<String> selfApproval =
                postRaw(hr, "/api/runs/" + runId + "/approve", "{\"comment\":\"自批\"}");
        assertEquals(400, selfApproval.statusCode(), selfApproval.body());
        assertTrue(selfApproval.body().contains("不能审批自己"), selfApproval.body());

        String approved =
                post(admin, "/api/runs/" + runId + "/approve", "{\"comment\":\"同意\"}");
        assertTrue(approved.contains("\"APPROVED\""), approved);
        assertTrue(approved.contains("\"APPROVE\""), approved);

        assertTrue(
                post(admin, "/api/runs/" + runId + "/finalize", "").contains("\"FINALIZED\""));

        HttpClient viewer = newClient();
        login(viewer, "viewer_approval", "viewer-approval-pass");
        assertEquals(
                403,
                postRaw(viewer, "/api/runs/preview",
                                "{\"period\":\"2026-10\",\"scope\":\"全部\",\"adjustments\":[]}")
                        .statusCode());
        assertEquals(200, getRaw(viewer, "/api/runs").statusCode());
        assertEquals(403, getRaw(viewer, "/api/approval/flows").statusCode());
        assertEquals(403, getRaw(viewer, "/api/approval/tasks").statusCode());
    }

    @Test
    @DisplayName("驳回需填写原因，驳回后 HR 可重新提交并经管理员通过")
    void supportsRejectAndResubmit() throws Exception {
        HttpClient admin = newClient();
        login(admin, "admin", ADMIN_PASSWORD);
        createUser(admin, "hr_reject", "hr-reject-pass", "HR");
        HttpClient hr = newClient();
        login(hr, "hr_reject", "hr-reject-pass");

        long runId = preview(hr);
        post(hr, "/api/runs/" + runId + "/submit-approval", "{\"comment\":\"提交\"}");

        HttpResponse<String> blankReason =
                postRaw(admin, "/api/runs/" + runId + "/reject", "{\"reason\":\"  \"}");
        assertEquals(400, blankReason.statusCode(), blankReason.body());
        assertTrue(blankReason.body().contains("驳回原因"), blankReason.body());

        String rejected =
                post(admin, "/api/runs/" + runId + "/reject", "{\"reason\":\"绩效说明不完整\"}");
        assertTrue(rejected.contains("\"REJECTED\""), rejected);
        assertTrue(rejected.contains("绩效说明不完整"), rejected);

        String resubmitted =
                post(hr, "/api/runs/" + runId + "/submit-approval", "{\"comment\":\"已补充\"}");
        assertTrue(resubmitted.contains("\"PENDING_APPROVAL\""), resubmitted);

        assertTrue(
                post(admin, "/api/runs/" + runId + "/approve", "{\"comment\":\"通过\"}")
                        .contains("\"APPROVED\""));
        String history = get(admin, "/api/runs/" + runId + "/approvals");
        assertTrue(history.contains("\"SUBMIT\""), history);
        assertTrue(history.contains("\"REJECT\""), history);
        assertTrue(history.contains("\"APPROVE\""), history);
    }

    @Test
    @DisplayName("管理员可维护审批链，同范围重复生效与未知部门被拒绝")
    void managesApprovalFlows() throws Exception {
        HttpClient admin = newClient();
        login(admin, "admin", ADMIN_PASSWORD);

        String flows = get(admin, "/api/approval/flows");
        assertTrue(flows.contains("默认审批链"), flows);

        String deptFlow =
                """
                {"name":"研发部审批链","scope_type":"DEPARTMENT","scope_value":"研发部",
                 "min_amount":0,"priority":10,"enabled":true,"description":"研发部专属"}
                """;
        assertTrue(post(admin, "/api/approval/flows", deptFlow).contains("研发部审批链"));

        HttpResponse<String> duplicate =
                postRaw(admin, "/api/approval/flows", deptFlow);
        assertEquals(400, duplicate.statusCode(), duplicate.body());
        assertTrue(duplicate.body().contains("已存在生效审批链"), duplicate.body());

        HttpResponse<String> unknownDepartment =
                postRaw(
                        admin,
                        "/api/approval/flows",
                        "{\"name\":\"未知部门链\",\"scope_type\":\"DEPARTMENT\","
                                + "\"scope_value\":\"星际部\"}");
        assertEquals(400, unknownDepartment.statusCode(), unknownDepartment.body());
        assertTrue(unknownDepartment.body().contains("部门不存在"), unknownDepartment.body());

        String stepFlow =
                """
                {"name":"财务部两级审批","scope_type":"DEPARTMENT","scope_value":"财务部",
                 "min_amount":0,"priority":10,"enabled":true,"steps":[
                   {"name":"财务复核","approver_type":"ROLE","approver_value":"HR"},
                   {"name":"管理员确认","approver_type":"USER","approver_value":"admin"}]}
                """;
        String created = post(admin, "/api/approval/flows", stepFlow);
        assertTrue(created.contains("财务复核"), created);
        assertTrue(created.contains("\"approver_type\":\"ROLE\""), created);

        String approvers = get(admin, "/api/approval/approvers");
        assertTrue(approvers.contains("\"roles\""), approvers);
        assertTrue(approvers.contains("\"users\""), approvers);
    }

    private long preview(HttpClient client) throws Exception {
        String body =
                post(client, "/api/runs/preview",
                        "{\"period\":\"2026-10\",\"scope\":\"全部\",\"adjustments\":[]}");
        Matcher matcher = Pattern.compile("\"run_id\":(\\d+)").matcher(body);
        assertTrue(matcher.find(), body);
        return Long.parseLong(matcher.group(1));
    }

    private void createUser(HttpClient admin, String username, String password, String role)
            throws Exception {
        String body =
                """
                {"username":"%s","display_name":"%s","password":"%s","role_code":"%s"}
                """
                        .formatted(username, username, password, role);
        String created = post(admin, "/api/admin/users", body);
        assertTrue(created.contains("\"username\":\"" + username + "\""), created);
    }

    private void login(HttpClient client, String username, String password) throws Exception {
        HttpResponse<String> response =
                postRaw(
                        client,
                        "/api/auth/login",
                        "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
        assertEquals(200, response.statusCode(), response.body());
    }

    private HttpClient newClient() {
        CookieManager cookies = new CookieManager();
        cookies.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        return HttpClient.newBuilder().cookieHandler(cookies).build();
    }

    private HttpResponse<String> getRaw(HttpClient client, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create(baseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String get(HttpClient client, String path) throws Exception {
        HttpResponse<String> response = getRaw(client, path);
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private String post(HttpClient client, String path, String body) throws Exception {
        HttpResponse<String> response = postRaw(client, path, body);
        assertEquals(200, response.statusCode(), response.body());
        return response.body();
    }

    private HttpResponse<String> postRaw(HttpClient client, String path, String body)
            throws Exception {
        HttpRequest request =
                HttpRequest.newBuilder(URI.create(baseUrl() + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
        return client.send(
                request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private static Path tempDir() {
        try {
            return Files.createTempDirectory("payroll-approval-web-");
        } catch (IOException e) {
            throw new IllegalStateException("无法创建测试输出目录", e);
        }
    }
}
