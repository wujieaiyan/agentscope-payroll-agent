package io.agentscope.examples.payroll.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
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
import io.agentscope.examples.payroll.service.PayrollRunService;
import io.agentscope.examples.payroll.service.PayrollServices;
import io.agentscope.examples.payroll.support.JsonSupport;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本地 Web 管理台：员工、考核、城市政策、考勤与工资批次。
 *
 * <p>仅绑定 127.0.0.1，无登录鉴权，定位是单机 HR 工具；不要直接暴露到公网。
 * 邮件发送固定走 dry-run 网关，不会真实投递。
 */
public final class PayrollWebServer {

    private static final Logger log = LoggerFactory.getLogger(PayrollWebServer.class);
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    private static final int MAX_UPLOAD_BYTES = 10 * 1024 * 1024;

    private final PayrollServices services;
    private final HttpServer server;
    private final ExecutorService executor;

    public PayrollWebServer(PayrollServices services, int port) throws IOException {
        this.services = services;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        this.executor = Executors.newFixedThreadPool(8);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    public void start() {
        server.start();
        log.info("薪酬管理台已启动：http://127.0.0.1:{}", port());
    }

    public void stop() {
        server.stop(0);
        executor.shutdownNow();
        services.close();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String[] parts = splitPath(exchange.getRequestURI().getRawPath());
            if (parts.length > 0 && "api".equals(parts[0])) {
                handleApi(exchange, parts);
            } else {
                serveStatic(exchange, exchange.getRequestURI().getPath());
            }
        } catch (PayrollException | IllegalArgumentException e) {
            json(exchange, 400, error(e.getMessage()));
        } catch (Exception e) {
            log.error("管理台请求失败：{} {}", exchange.getRequestMethod(),
                    exchange.getRequestURI(), e);
            json(exchange, 500, error("服务器内部错误：" + e.getMessage()));
        } finally {
            exchange.close();
        }
    }

    private void handleApi(HttpExchange exchange, String[] parts) throws IOException {
        String method = exchange.getRequestMethod();
        if (parts.length < 2) {
            json(exchange, 404, error("接口不存在"));
            return;
        }
        switch (parts[1]) {
            case "health" -> {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("status", "UP");
                payload.put("data_through_period", services.engine().dataThroughPeriod());
                payload.put("database", services.database().url());
                payload.put("email_mode", "DRY_RUN");
                payload.put("chat_model", services.chat().modelId());
                ok(exchange, payload);
            }
            case "chat" -> handleChat(exchange, method, parts);
            case "import" -> handleImport(exchange, method, parts);
            case "employees" -> handleEmployees(exchange, method, parts);
            case "reviews" -> handleReviews(exchange, method, parts);
            case "policies" -> handlePolicies(exchange, method, parts);
            case "attendance" -> handleAttendance(exchange, method, parts);
            case "runs" -> handleRuns(exchange, method, parts);
            default -> json(exchange, 404, error("接口不存在：" + parts[1]));
        }
    }

    private void handleEmployees(HttpExchange exchange, String method, String[] parts)
            throws IOException {
        H2PayrollRepository repository = services.repository();
        if (parts.length == 2 && "GET".equals(method)) {
            boolean includeInactive =
                    "true".equalsIgnoreCase(queryParam(exchange, "include_inactive"));
            ok(exchange, repository.employeeViews(includeInactive));
            return;
        }
        if (parts.length == 2 && "POST".equals(method)) {
            Employee employee = requireBody(exchange, Employee.class);
            repository.saveEmployee(employee);
            ok(
                    exchange,
                    repository
                            .findEmployeeView(employee.employeeId())
                            .orElseThrow(() -> new PayrollException("员工保存后无法读取")));
            return;
        }
        if (parts.length == 4
                && "POST".equals(method)
                && "deactivate".equals(parts[3])) {
            repository.deactivateEmployee(parts[2]);
            ok(exchange, Map.of("employee_id", parts[2], "active", false));
            return;
        }
        json(exchange, 404, error("员工接口不存在"));
    }

    private void handleReviews(HttpExchange exchange, String method, String[] parts)
            throws IOException {
        H2PayrollRepository repository = services.repository();
        if (parts.length == 2 && "GET".equals(method)) {
            Map<String, String> names = new LinkedHashMap<>();
            for (Employee employee : repository.allEmployees(true)) {
                names.put(employee.employeeId(), employee.name());
            }
            var views =
                    repository.reviews(queryParam(exchange, "period")).stream()
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
            ok(exchange, views);
            return;
        }
        if (parts.length == 2 && "POST".equals(method)) {
            PerformanceReview review = requireBody(exchange, PerformanceReview.class);
            if (repository.findEmployee(review.employeeId()).isEmpty()) {
                throw new PayrollException(
                        "员工不存在或已停用：" + review.employeeId());
            }
            ok(exchange, repository.saveReview(review));
            return;
        }
        if (parts.length == 4 && "DELETE".equals(method)) {
            repository.deleteReview(parts[2], parts[3]);
            ok(exchange, Map.of("deleted", true));
            return;
        }
        json(exchange, 404, error("考核接口不存在"));
    }

    private void handlePolicies(HttpExchange exchange, String method, String[] parts)
            throws IOException {
        H2PayrollRepository repository = services.repository();
        if (parts.length == 2 && "GET".equals(method)) {
            ok(exchange, repository.policies());
            return;
        }
        if (parts.length == 3 && "PUT".equals(method)) {
            PayrollPolicy policy = requireBody(exchange, PayrollPolicy.class);
            repository.savePolicy(new CityPolicy(parts[2], policy, null));
            ok(exchange, Map.of("city", parts[2], "saved", true));
            return;
        }
        json(exchange, 404, error("城市政策接口不存在"));
    }

    private void handleAttendance(HttpExchange exchange, String method, String[] parts)
            throws IOException {
        H2PayrollRepository repository = services.repository();
        if (parts.length == 2 && "GET".equals(method)) {
            ok(exchange, repository.attendanceForPeriod(requirePeriod(exchange)));
            return;
        }
        if (parts.length == 4 && "PUT".equals(method)) {
            AttendanceSummary attendance = requireBody(exchange, AttendanceSummary.class);
            if (!parts[2].equalsIgnoreCase(attendance.employeeId())
                    || !parts[3].equals(attendance.period())) {
                throw new PayrollException("路径中的员工/期间与请求体不一致");
            }
            if (repository.findEmployee(attendance.employeeId()).isEmpty()) {
                throw new PayrollException("员工不存在或已停用：" + attendance.employeeId());
            }
            ok(exchange, repository.saveAttendance(attendance));
            return;
        }
        json(exchange, 404, error("考勤接口不存在"));
    }

    private void handleChat(HttpExchange exchange, String method, String[] parts)
            throws IOException {
        if (parts.length == 2 && "POST".equals(method)) {
            ChatRequest request = requireBody(exchange, ChatRequest.class);
            String message = request.message() == null ? "" : request.message().trim();
            if (message.isEmpty()) {
                throw new PayrollException("请输入要咨询的问题");
            }
            if (message.length() > 2000) {
                throw new PayrollException("问题过长，请控制在 2000 字以内");
            }
            ok(exchange, services.chat().chat(message));
            return;
        }
        if (parts.length == 3 && "reset".equals(parts[2]) && "POST".equals(method)) {
            services.chat().reset();
            ok(exchange, Map.of("reset", true));
            return;
        }
        json(exchange, 404, error("对话接口不存在"));
    }

    private void handleImport(HttpExchange exchange, String method, String[] parts)
            throws IOException {
        ExcelImportService importer = services.excelImportService();
        if (parts.length == 4 && "templates".equals(parts[2]) && "GET".equals(method)) {
            ImportType type = ImportType.fromPath(parts[3]);
            sendXlsx(
                    exchange,
                    importer.template(type),
                    "payroll-" + type.key() + "-template.xlsx");
            return;
        }
        if (parts.length == 3 && "POST".equals(method)) {
            ImportType type = ImportType.fromPath(parts[2]);
            ok(exchange, importer.importWorkbook(type, readBinaryBody(exchange)));
            return;
        }
        json(exchange, 404, error("导入接口不存在"));
    }

    private static void sendXlsx(HttpExchange exchange, byte[] body, String filename)
            throws IOException {
        exchange.getResponseHeaders()
                .set(
                        "Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        exchange.getResponseHeaders()
                .set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private void handleRuns(HttpExchange exchange, String method, String[] parts)
            throws IOException {
        PayrollRunService runService = services.runService();
        if (parts.length == 2 && "GET".equals(method)) {
            ok(exchange, runService.runs());
            return;
        }
        if (parts.length == 3 && "GET".equals(method)) {
            ok(exchange, runService.detail(parseLong(parts[2], "批次号")));
            return;
        }
        if (parts.length == 2 && "POST".equals(method)) {
            json(exchange, 404, error("请使用 /api/runs/preview"));
            return;
        }
        if (parts.length == 3 && "preview".equals(parts[2]) && "POST".equals(method)) {
            PreviewRequest request = requireBody(exchange, PreviewRequest.class);
            ok(
                    exchange,
                    runService.preview(
                            request.period(),
                            request.scope(),
                            PayrollRunService.indexAdjustments(request.adjustments())));
            return;
        }
        if (parts.length == 4 && "finalize".equals(parts[3]) && "POST".equals(method)) {
            ok(exchange, runService.finalizeRun(parseLong(parts[2], "批次号")));
            return;
        }
        if (parts.length == 4 && "send".equals(parts[3]) && "POST".equals(method)) {
            SendRequest request = readBody(exchange, SendRequest.class);
            boolean confirm = request != null && request.confirm();
            ok(exchange, runService.sendAll(parseLong(parts[2], "批次号"), confirm));
            return;
        }
        if (parts.length == 4 && "excel".equals(parts[3]) && "GET".equals(method)) {
            downloadExcel(exchange, parseLong(parts[2], "批次号"));
            return;
        }
        json(exchange, 404, error("工资批次接口不存在"));
    }

    private void downloadExcel(HttpExchange exchange, long runId) throws IOException {
        PayrollRun run = services.runService().detail(runId).run();
        if (run.excelPath() == null || run.excelPath().isBlank()) {
            throw new PayrollException("该批次尚未生成 Excel，请先完成出账");
        }
        Path file = Path.of(run.excelPath());
        if (!Files.isRegularFile(file)) {
            throw new PayrollException("Excel 文件不存在，请重新出账：" + file);
        }
        exchange.getResponseHeaders()
                .set(
                        "Content-Type",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        exchange.getResponseHeaders()
                .set(
                        "Content-Disposition",
                        "attachment; filename=\"" + file.getFileName() + "\"");
        exchange.sendResponseHeaders(200, Files.size(file));
        try (OutputStream out = exchange.getResponseBody()) {
            Files.copy(file, out);
        }
    }

    private void serveStatic(HttpExchange exchange, String path) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            json(exchange, 405, error("仅支持 GET"));
            return;
        }
        String resource =
                switch (path) {
                    case "/", "/index.html" -> "web/index.html";
                    case "/app.js" -> "web/app.js";
                    case "/style.css" -> "web/style.css";
                    default -> null;
                };
        if (resource == null) {
            byte[] body = "页面不存在".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders()
                    .set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(404, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            return;
        }
        try (InputStream in =
                PayrollWebServer.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("缺少静态资源：" + resource);
            }
            byte[] body = in.readAllBytes();
            exchange.getResponseHeaders().set("Content-Type", contentType(resource));
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }

    private static String contentType(String resource) {
        if (resource.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (resource.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        return "text/html; charset=utf-8";
    }

    private static String[] splitPath(String rawPath) {
        String trimmed = rawPath.startsWith("/") ? rawPath.substring(1) : rawPath;
        if (trimmed.isEmpty()) {
            return new String[0];
        }
        String[] raw = trimmed.split("/");
        String[] decoded = new String[raw.length];
        for (int i = 0; i < raw.length; i++) {
            decoded[i] =
                    URLDecoder.decode(raw[i], StandardCharsets.UTF_8);
        }
        return decoded;
    }

    private static String queryParam(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null || query.isBlank()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int index = pair.indexOf('=');
            if (index < 0) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8);
            if (name.equals(key)) {
                return URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String requirePeriod(HttpExchange exchange) {
        String period = queryParam(exchange, "period");
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

    private static <T> T requireBody(HttpExchange exchange, Class<T> type) throws IOException {
        T body = readBody(exchange, type);
        if (body == null) {
            throw new PayrollException("请求体不能为空");
        }
        return body;
    }

    private static <T> T readBody(HttpExchange exchange, Class<T> type) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_JSON_BYTES + 1);
        if (bytes.length > MAX_JSON_BYTES) {
            throw new PayrollException("请求体过大");
        }
        if (bytes.length == 0) {
            return null;
        }
        return JsonSupport.fromJson(new String(bytes, StandardCharsets.UTF_8), type);
    }

    private static byte[] readBinaryBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_UPLOAD_BYTES + 1);
        if (bytes.length > MAX_UPLOAD_BYTES) {
            throw new PayrollException("Excel 文件过大，最大支持 10 MB");
        }
        if (bytes.length == 0) {
            throw new PayrollException("请上传 Excel 文件");
        }
        return bytes;
    }

    private static void ok(HttpExchange exchange, Object data) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "OK");
        payload.put("data", data);
        json(exchange, 200, payload);
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "ERROR");
        payload.put("message", message);
        return payload;
    }

    private static void json(HttpExchange exchange, int status, Object payload)
            throws IOException {
        byte[] body =
                JsonSupport.toJson(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** 预览请求：period + scope + 可选微调。 */
    public record PreviewRequest(
            String period, String scope,
            List<PayrollRunService.AdjustmentInput> adjustments) {
    }

    /** 发送请求：必须显式 confirm=true。 */
    public record SendRequest(boolean confirm) {
    }

    /** 对话请求。 */
    public record ChatRequest(String message) {
    }
}
