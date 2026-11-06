package io.agentscope.examples.payroll.web;

import io.agentscope.examples.payroll.db.H2PayrollRepository;
import io.agentscope.examples.payroll.domain.AttendanceSummary;
import io.agentscope.examples.payroll.domain.CityPolicy;
import io.agentscope.examples.payroll.domain.Employee;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollPolicy;
import io.agentscope.examples.payroll.domain.PayrollRun;
import io.agentscope.examples.payroll.domain.PerformanceReview;
import io.agentscope.examples.payroll.domain.ReviewView;
import io.agentscope.examples.payroll.service.ExcelImportService;
import io.agentscope.examples.payroll.service.ExcelImportService.ImportType;
import io.agentscope.examples.payroll.service.PayrollChat;
import io.agentscope.examples.payroll.service.PayrollRunService;
import io.agentscope.examples.payroll.service.PayrollServices;
import io.agentscope.examples.payroll.support.Permissions;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 薪酬管理台 REST API。
 *
 * <p>接口按角色权限校验；登录认证由 {@link AuthInterceptor} 统一处理。
 * 邮件发送固定走 dry-run 网关。
 */
@RestController
@RequestMapping("/api")
public class PayrollWebController {

    private static final Logger log = LoggerFactory.getLogger(PayrollWebController.class);
    private static final MediaType XLSX =
            MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    private static final int MAX_UPLOAD_BYTES = 10 * 1024 * 1024;
    private static final long SSE_TIMEOUT_MS = 600_000L;

    private final PayrollServices services;
    private final Executor chatStreamExecutor;

    public PayrollWebController(
            PayrollServices services,
            @Qualifier("chatStreamExecutor") Executor chatStreamExecutor) {
        this.services = services;
        this.chatStreamExecutor = chatStreamExecutor;
    }

    @GetMapping("/health")
    public ApiResponse health() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "UP");
        payload.put("data_through_period", services.engine().dataThroughPeriod());
        payload.put("database", services.database().url());
        payload.put("email_mode", "DRY_RUN");
        payload.put("chat_model", services.chat().modelId());
        return ApiResponse.ok(payload);
    }

    @GetMapping("/employees")
    @RequirePermission(Permissions.EMPLOYEE_READ)
    public ApiResponse employees(
            @RequestParam(name = "include_inactive", defaultValue = "false")
                    boolean includeInactive) {
        return ApiResponse.ok(services.repository().employeeViews(includeInactive));
    }

    @PostMapping("/employees")
    @RequirePermission(Permissions.EMPLOYEE_WRITE)
    public ApiResponse saveEmployee(@RequestBody Employee employee) {
        H2PayrollRepository repository = services.repository();
        repository.saveEmployee(employee);
        return ApiResponse.ok(
                repository
                        .findEmployeeView(employee.employeeId())
                        .orElseThrow(() -> new PayrollException("员工保存后无法读取")));
    }

    @PostMapping("/employees/{employeeId}/deactivate")
    @RequirePermission(Permissions.EMPLOYEE_WRITE)
    public ApiResponse deactivateEmployee(@PathVariable String employeeId) {
        services.repository().deactivateEmployee(employeeId);
        return ApiResponse.ok(Map.of("employee_id", employeeId, "active", false));
    }

    @GetMapping("/reviews")
    @RequirePermission(Permissions.REVIEW_READ)
    public ApiResponse reviews(@RequestParam(required = false) String period) {
        H2PayrollRepository repository = services.repository();
        Map<String, String> names = new LinkedHashMap<>();
        for (Employee employee : repository.allEmployees(true)) {
            names.put(employee.employeeId(), employee.name());
        }
        List<ReviewView> views =
                repository.reviews(period).stream()
                        .map(
                                review ->
                                        new ReviewView(
                                                review.employeeId(),
                                                names.getOrDefault(review.employeeId(), ""),
                                                review.period(),
                                                review.score(),
                                                review.coefficient(),
                                                review.comment()))
                        .toList();
        return ApiResponse.ok(views);
    }

    @PostMapping("/reviews")
    @RequirePermission(Permissions.REVIEW_WRITE)
    public ApiResponse saveReview(@RequestBody PerformanceReview review) {
        H2PayrollRepository repository = services.repository();
        if (repository.findEmployee(review.employeeId()).isEmpty()) {
            throw new PayrollException("员工不存在或已停用：" + review.employeeId());
        }
        return ApiResponse.ok(repository.saveReview(review));
    }

    @DeleteMapping("/reviews/{employeeId}/{period}")
    @RequirePermission(Permissions.REVIEW_WRITE)
    public ApiResponse deleteReview(
            @PathVariable String employeeId, @PathVariable String period) {
        services.repository().deleteReview(employeeId, period);
        return ApiResponse.ok(Map.of("deleted", true));
    }

    @GetMapping("/policies")
    @RequirePermission(Permissions.POLICY_READ)
    public ApiResponse policies() {
        return ApiResponse.ok(services.repository().policies());
    }

    @PutMapping("/policies/{city}")
    @RequirePermission(Permissions.POLICY_WRITE)
    public ApiResponse savePolicy(
            @PathVariable String city, @RequestBody PayrollPolicy policy) {
        services.repository().savePolicy(new CityPolicy(city, policy, null));
        return ApiResponse.ok(Map.of("city", city, "saved", true));
    }

    @GetMapping("/attendance")
    @RequirePermission(Permissions.ATTENDANCE_READ)
    public ApiResponse attendance(@RequestParam(required = false) String period) {
        return ApiResponse.ok(
                services.repository().attendanceForPeriod(requirePeriod(period)));
    }

    @PutMapping("/attendance/{employeeId}/{period}")
    @RequirePermission(Permissions.ATTENDANCE_WRITE)
    public ApiResponse saveAttendance(
            @PathVariable String employeeId,
            @PathVariable String period,
            @RequestBody AttendanceSummary attendance) {
        if (!employeeId.equalsIgnoreCase(attendance.employeeId())
                || !period.equals(attendance.period())) {
            throw new PayrollException("路径中的员工/期间与请求体不一致");
        }
        H2PayrollRepository repository = services.repository();
        if (repository.findEmployee(attendance.employeeId()).isEmpty()) {
            throw new PayrollException("员工不存在或已停用：" + attendance.employeeId());
        }
        return ApiResponse.ok(repository.saveAttendance(attendance));
    }

    @PostMapping("/chat")
    @RequirePermission(Permissions.CHAT_USE)
    public ApiResponse chat(@RequestBody(required = false) ChatRequest request) {
        return ApiResponse.ok(services.chat().chat(requireChatMessage(request)));
    }

    /**
     * 流式对话：以 SSE 推送思考、工具调用与回答增量。
     *
     * <p>这是 POST 接口，浏览器 EventSource 不支持 POST，前端用 fetch 读取
     * {@code text/event-stream} 并自行解析事件。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @RequirePermission(Permissions.CHAT_USE)
    public SseEmitter chatStream(@RequestBody(required = false) ChatRequest request) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitter.onTimeout(emitter::complete);
        emitter.onError(error -> emitter.complete());
        String message;
        try {
            message = requireChatMessage(request);
        } catch (PayrollException e) {
            // 客户端只接受 text/event-stream，校验错误也必须以 SSE 事件返回。
            ChatSse.Channel channel = new ChatSse.Channel(emitter);
            channel.send("error", ChatSse.payload("message", e.getMessage()));
            emitter.complete();
            return emitter;
        }
        chatStreamExecutor.execute(() -> streamChat(message, emitter));
        return emitter;
    }

    private void streamChat(String message, SseEmitter emitter) {
        ChatSse.Channel channel = new ChatSse.Channel(emitter);
        long startedAt = System.currentTimeMillis();
        log.info("[SSE] 对话开始：{}", abbreviate(message, 120));
        try {
            services.chat()
                    .stream(message, ChatSse.bridge(channel, reply -> { }));
            emitter.complete();
            log.info("[SSE] 对话完成：耗时={}ms", System.currentTimeMillis() - startedAt);
        } catch (PayrollException e) {
            log.warn(
                    "[SSE] 对话失败：耗时={}ms，原因={}",
                    System.currentTimeMillis() - startedAt,
                    e.getMessage());
            channel.send("error", ChatSse.payload("message", e.getMessage()));
            emitter.complete();
        } catch (RuntimeException e) {
            log.error("[SSE] 流式对话异常", e);
            channel.send("error", ChatSse.payload("message", "对话失败，请查看服务端日志"));
            emitter.complete();
        }
    }

    @PostMapping("/chat/reset")
    @RequirePermission(Permissions.CHAT_USE)
    public ApiResponse resetChat() {
        services.chat().reset();
        return ApiResponse.ok(Map.of("reset", true));
    }

    @GetMapping("/import/templates/{type}")
    @RequirePermission(Permissions.IMPORT_USE)
    public ResponseEntity<byte[]> downloadTemplate(@PathVariable String type) {
        ImportType importType = ImportType.fromPath(type);
        byte[] body = services.excelImportService().template(importType);
        return ResponseEntity.ok()
                .contentType(XLSX)
                .cacheControl(CacheControl.noStore())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        attachment("payroll-" + importType.key() + "-template.xlsx"))
                .body(body);
    }

    @PostMapping("/import/{type}")
    @RequirePermission(Permissions.IMPORT_USE)
    public ApiResponse importExcel(
            @PathVariable String type, @RequestBody(required = false) byte[] body) {
        ImportType importType = ImportType.fromPath(type);
        byte[] bytes = body == null ? new byte[0] : body;
        if (bytes.length > MAX_UPLOAD_BYTES) {
            throw new PayrollException("Excel 文件过大，最大支持 10 MB");
        }
        return ApiResponse.ok(
                services.excelImportService().importWorkbook(importType, bytes));
    }

    @GetMapping("/runs")
    @RequirePermission(Permissions.PAYROLL_READ)
    public ApiResponse runs() {
        return ApiResponse.ok(services.runService().runs());
    }

    @GetMapping("/runs/{runId}")
    @RequirePermission(Permissions.PAYROLL_READ)
    public ApiResponse runDetail(@PathVariable String runId) {
        return ApiResponse.ok(services.runService().detail(parseLong(runId, "批次号")));
    }

    @PostMapping("/runs/preview")
    @RequirePermission(Permissions.PAYROLL_WRITE)
    public ApiResponse preview(@RequestBody PreviewRequest request) {
        PayrollRunService runService = services.runService();
        return ApiResponse.ok(
                runService.preview(
                        request.period(),
                        request.scope(),
                        PayrollRunService.indexAdjustments(request.adjustments())));
    }

    @PostMapping("/runs/{runId}/finalize")
    @RequirePermission(Permissions.PAYROLL_WRITE)
    public ApiResponse finalizeRun(@PathVariable String runId) {
        return ApiResponse.ok(
                services.runService().finalizeRun(parseLong(runId, "批次号")));
    }

    @PostMapping("/runs/{runId}/send")
    @RequirePermission(Permissions.PAYROLL_WRITE)
    public ApiResponse sendRun(
            @PathVariable String runId,
            @RequestBody(required = false) SendRequest request) {
        boolean confirm = request != null && request.confirm();
        return ApiResponse.ok(
                services.runService().sendAll(parseLong(runId, "批次号"), confirm));
    }

    @GetMapping("/runs/{runId}/excel")
    @RequirePermission(Permissions.PAYROLL_READ)
    public ResponseEntity<Resource> downloadExcel(@PathVariable String runId)
            throws IOException {
        PayrollRun run =
                services.runService().detail(parseLong(runId, "批次号")).run();
        if (run.excelPath() == null || run.excelPath().isBlank()) {
            throw new PayrollException("该批次尚未生成 Excel，请先完成出账");
        }
        Path file = Path.of(run.excelPath());
        if (!Files.isRegularFile(file)) {
            throw new PayrollException("Excel 文件不存在，请重新出账：" + file);
        }
        return ResponseEntity.ok()
                .contentType(XLSX)
                .cacheControl(CacheControl.noStore())
                .contentLength(Files.size(file))
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        attachment(file.getFileName().toString()))
                .body(new FileSystemResource(file));
    }

    private static String attachment(String filename) {
        return ContentDisposition.attachment()
                .filename(filename, StandardCharsets.UTF_8)
                .build()
                .toString();
    }

    private static String requirePeriod(String period) {
        if (period == null || period.isBlank()) {
            throw new PayrollException("请提供 period 参数，格式 YYYY-MM");
        }
        try {
            return YearMonth.parse(period.trim()).toString();
        } catch (DateTimeParseException e) {
            throw new PayrollException("期间格式错误：" + period + "，应为 YYYY-MM");
        }
    }

    private static long parseLong(String value, String label) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new PayrollException(label + "必须是数字：" + value);
        }
    }

    private static String requireChatMessage(ChatRequest request) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            throw new PayrollException("请输入要咨询的问题");
        }
        String message = request.message().trim();
        if (message.length() > 2000) {
            throw new PayrollException("问题过长，请控制在 2000 字以内");
        }
        return message;
    }

    private static String abbreviate(String text, int limit) {
        String flattened = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return flattened.length() <= limit ? flattened : flattened.substring(0, limit) + "…";
    }

    /** 预览请求：period + scope + 可选微调。 */
    public record PreviewRequest(
            String period,
            String scope,
            List<PayrollRunService.AdjustmentInput> adjustments) {
    }

    /** 发送请求：必须显式 confirm=true。 */
    public record SendRequest(boolean confirm) {
    }

    /** 对话请求。 */
    public record ChatRequest(String message) {
    }
}
