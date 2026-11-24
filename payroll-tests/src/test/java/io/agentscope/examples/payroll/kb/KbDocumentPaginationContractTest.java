package io.agentscope.examples.payroll.kb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.kb.KbDtos.DocumentView;
import io.agentscope.examples.payroll.kb.KbDocumentService.DocumentPage;
import io.agentscope.examples.payroll.service.AuthService;
import io.agentscope.examples.payroll.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/** 文档列表双契约：不带游标仍返回裸数组，带游标返回 {items, next_cursor}。 */
class KbDocumentPaginationContractTest {

    private static final AuthUser USER = AuthUser.system();

    @Test
    @DisplayName("不带 cursor/limit 时保持裸数组旧契约")
    void listWithoutCursorReturnsBareArray() {
        KbDocumentService service = mock(KbDocumentService.class);
        AuthService auth = mock(AuthService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(auth.currentUser(request)).thenReturn(USER);
        List<DocumentView> documents = List.of();
        when(service.list(eq(3L), isNull(), isNull(), isNull(), eq(USER))).thenReturn(documents);

        ApiResponse response = controller(service, auth).list(3L, null, null, null, null, null, request);

        assertEquals(documents, response.data());
        assertInstanceOf(List.class, response.data());
    }

    @Test
    @DisplayName("带游标时返回 items 与 next_cursor，limit 缺省用配置页大小")
    void listWithCursorReturnsItemsAndNextCursor() {
        KbDocumentService service = mock(KbDocumentService.class);
        AuthService auth = mock(AuthService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(auth.currentUser(request)).thenReturn(USER);
        List<DocumentView> documents = List.of();
        when(service.listPage(eq(3L), isNull(), isNull(), isNull(), eq("c1"), eq(100), eq(USER)))
                .thenReturn(new DocumentPage(documents, "c2"));

        ApiResponse response = controller(service, auth).list(3L, null, null, null, "c1", null, request);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.data();
        assertEquals(documents, body.get("items"));
        assertEquals("c2", body.get("next_cursor"));
    }

    private static KbDocumentController controller(
            KbDocumentService service, AuthService auth) {
        return new KbDocumentController(service, auth, DataSize.ofMegabytes(512), 100);
    }
}
