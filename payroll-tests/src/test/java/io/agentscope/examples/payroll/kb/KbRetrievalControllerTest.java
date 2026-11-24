package io.agentscope.examples.payroll.kb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.examples.payroll.domain.AuthUser;
import io.agentscope.examples.payroll.kb.KbDtos.RetrievalResult;
import io.agentscope.examples.payroll.kb.KbDtos.RetrievalTestRequest;
import io.agentscope.examples.payroll.service.AuthService;
import io.agentscope.examples.payroll.web.ApiResponse;
import io.agentscope.examples.payroll.web.TooManyRequestsException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 管理台显式检索：与聊天链路共用池，但池满必须快速回 429，不得排队或挂起。 */
class KbRetrievalControllerTest {

    private static final AuthUser USER = AuthUser.system();
    private static final RetrievalTestRequest BODY =
            new RetrievalTestRequest("年假多少天", 5, 0.5, false);

    @Test
    @DisplayName("检索池满时回 429，且不进入检索服务")
    void rejectedSubmissionReturns429() throws Exception {
        KbRetrievalService service = mock(KbRetrievalService.class);
        AuthService auth = mock(AuthService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(auth.currentUser(request)).thenReturn(USER);

        ThreadPoolExecutor executor =
                new ThreadPoolExecutor(
                        1,
                        1,
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(1),
                        new ThreadPoolExecutor.AbortPolicy());
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            executor.submit(
                    () -> {
                        running.countDown();
                        await(release);
                    });
            running.await();
            executor.submit(() -> {});

            KbRetrievalController controller = new KbRetrievalController(service, auth, executor);
            assertThrows(
                    TooManyRequestsException.class,
                    () -> controller.test(1L, BODY, request));
            verifyNoInteractions(service);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("检索池空闲时同步返回服务结果")
    void successfulSearchReturnsResult() {
        KbRetrievalService service = mock(KbRetrievalService.class);
        AuthService auth = mock(AuthService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(auth.currentUser(request)).thenReturn(USER);
        RetrievalResult result =
                new RetrievalResult("年假多少天", "HYBRID", 12, List.of(), null, false, Map.of());
        when(service.search(eq(7L), any(RetrievalTestRequest.class), eq(USER))).thenReturn(result);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ApiResponse response =
                    new KbRetrievalController(service, auth, executor).test(7L, BODY, request);
            assertEquals(result, response.data());
        } finally {
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
