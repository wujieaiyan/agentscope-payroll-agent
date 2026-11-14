package io.agentscope.examples.payroll.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.H2AdminRepository;
import io.agentscope.examples.payroll.db.H2AuditStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.AuditEntry;
import io.agentscope.examples.payroll.domain.AuditPage;
import io.agentscope.examples.payroll.domain.AuditQuery;
import io.agentscope.examples.payroll.service.AuditService;
import io.agentscope.examples.payroll.service.AuthService;
import io.agentscope.examples.payroll.support.AuditContext;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class AuditFilterTest {

    private AuditService auditService;
    private AuditFilter filter;

    @BeforeEach
    void setUp() {
        PayrollDatabase database = PayrollDatabase.inMemory("audit-filter-" + UUID.randomUUID());
        auditService = new AuditService(new H2AuditStore(database), 180);
        AuthService authService =
                new AuthService(
                        new H2AdminRepository(database),
                        new BCryptPasswordEncoder(),
                        false);
        filter = new AuditFilter(auditService, authService);
    }

    @AfterEach
    void tearDown() {
        AuditContext.clear();
    }

    @Test
    void recordsGenericWriteWithRedactedBodyAndSystemActor() throws Exception {
        MockHttpServletRequest request = jsonPost("/api/departments", "{\"name\":\"研发部\","
                + "\"password\":\"p@ssw0rd\"}");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        AuditPage page = auditService.query(AuditQuery.firstPage(10));
        assertEquals(1, page.total());
        AuditEntry entry = page.items().get(0);
        assertEquals("post /api/departments", entry.action());
        assertEquals("endpoint", entry.resourceType());
        assertEquals("system", entry.actorName());
        assertEquals(AuditEntry.SUCCESS, entry.result());
        assertEquals(200, entry.statusCode());
        assertTrue(entry.payloadJson().contains("\"password\":\"***\""));
        assertFalse(entry.payloadJson().contains("p@ssw0rd"));
    }

    @Test
    void skipsRequestsAlreadyRecordedByController() throws Exception {
        MockHttpServletRequest request = jsonPost("/api/departments", "{\"name\":\"研发部\"}");
        MockHttpServletResponse response = new MockHttpServletResponse();
        HttpServlet controller =
                new HttpServlet() {
                    @Override
                    protected void service(
                            HttpServletRequest req, HttpServletResponse resp) {
                        auditService.record(
                                "department.create", "department", "3", null,
                                Map.of("name", "研发部"));
                        resp.setStatus(201);
                    }
                };

        filter.doFilter(request, response, new MockFilterChain(controller));

        AuditPage page = auditService.query(AuditQuery.firstPage(10));
        assertEquals(1, page.total());
        assertEquals("department.create", page.items().get(0).action());
        assertEquals(201, response.getStatus());
    }

    @Test
    void recordsRejectedWriteAsFailureWithInterceptorMessage() throws Exception {
        MockHttpServletRequest request = jsonPost("/api/employees", "{\"employee_id\":\"EMP009\"}");
        MockHttpServletResponse response = new MockHttpServletResponse();
        HttpServlet interceptor =
                new HttpServlet() {
                    @Override
                    protected void service(
                            HttpServletRequest req, HttpServletResponse resp) {
                        AuditContext.markFailure("无权执行该操作", 403);
                        resp.setStatus(403);
                    }
                };

        filter.doFilter(request, response, new MockFilterChain(interceptor));

        AuditPage page = auditService.query(AuditQuery.firstPage(10));
        assertEquals(1, page.total());
        AuditEntry entry = page.items().get(0);
        assertEquals(AuditEntry.FAILURE, entry.result());
        assertEquals(403, entry.statusCode());
        assertEquals("无权执行该操作", entry.message());
    }

    @Test
    void ignoresReadRequests() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/employees");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(0, auditService.query(AuditQuery.firstPage(10)).total());
    }

    private static MockHttpServletRequest jsonPost(String path, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setContentType("application/json");
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
