package io.agentscope.examples.payroll.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class NodeRoleFilterTest {

    @Test
    @DisplayName("runtime 只放行内部接口与健康检查")
    void runtimeOnlyServesInternalApi() throws Exception {
        NodeRoleFilter filter = new NodeRoleFilter("runtime");

        assertFiltered(filter, "/api/employees");
        assertAllowed(filter, "/internal/turns");
        assertAllowed(filter, "/actuator/health");
    }

    @Test
    @DisplayName("web 节点不暴露内部接口")
    void webHidesInternalApi() throws Exception {
        NodeRoleFilter filter = new NodeRoleFilter("web");

        assertFiltered(filter, "/internal/turns");
        assertAllowed(filter, "/api/employees");
    }

    @Test
    @DisplayName("all 角色保持单机行为")
    void allRoleServesEverything() throws Exception {
        NodeRoleFilter filter = new NodeRoleFilter(null);

        assertAllowed(filter, "/api/employees");
        assertAllowed(filter, "/internal/turns");
    }

    private static void assertFiltered(NodeRoleFilter filter, String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request(path), response, new MockFilterChain());
        assertEquals(404, response.getStatus());
        assertTrue(response.getContentAsString().contains("not found"));
    }

    private static void assertAllowed(NodeRoleFilter filter, String path) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request(path), response, chain);
        assertTrue(chain.getRequest() != null, "请求应继续传递：" + path);
    }

    private static MockHttpServletRequest request(String path) {
        return new MockHttpServletRequest("GET", path);
    }
}
