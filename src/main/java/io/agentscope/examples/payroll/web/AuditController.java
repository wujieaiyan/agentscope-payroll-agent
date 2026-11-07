package io.agentscope.examples.payroll.web;

import io.agentscope.examples.payroll.domain.AuditEntry;
import io.agentscope.examples.payroll.domain.AuditQuery;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.service.AuditService;
import io.agentscope.examples.payroll.support.Permissions;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 审计日志查询与 CSV 导出；仅拥有 audit:read 的角色可见。 */
@RestController
@RequestMapping("/api/audit")
@RequirePermission(Permissions.AUDIT_READ)
public class AuditController {

    private static final MediaType CSV = MediaType.parseMediaType("text/csv;charset=UTF-8");
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping
    public ApiResponse list(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(name = "resource_type", required = false) String resourceType,
            @RequestParam(name = "resource_id", required = false) String resourceId,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(
                auditService.query(
                        query(from, to, actor, action, resourceType, resourceId, result, keyword,
                                page, size)));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(name = "resource_type", required = false) String resourceType,
            @RequestParam(name = "resource_id", required = false) String resourceId,
            @RequestParam(required = false) String result,
            @RequestParam(required = false) String keyword) {
        List<AuditEntry> entries =
                auditService.export(
                        query(from, to, actor, action, resourceType, resourceId, result, keyword,
                                1, 200));
        byte[] body = csv(entries).getBytes(StandardCharsets.UTF_8);
        String filename =
                "audit-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                        + ".csv";
        return ResponseEntity.ok()
                .contentType(CSV)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(filename, StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(body);
    }

    private static AuditQuery query(
            String from,
            String to,
            String actor,
            String action,
            String resourceType,
            String resourceId,
            String result,
            String keyword,
            int page,
            int size) {
        return new AuditQuery(
                parseFrom(from),
                parseTo(to),
                blankToNull(actor),
                blankToNull(action),
                blankToNull(resourceType),
                blankToNull(resourceId),
                resultValue(result),
                blankToNull(keyword),
                Math.max(1, page),
                Math.min(Math.max(1, size), 200));
    }

    private static LocalDateTime parseFrom(String value) {
        if (isBlank(value)) {
            return null;
        }
        String text = value.trim();
        try {
            return LocalDate.parse(text).atStartOfDay();
        } catch (DateTimeParseException ignored) {
            return parseDateTime(text);
        }
    }

    private static LocalDateTime parseTo(String value) {
        if (isBlank(value)) {
            return null;
        }
        String text = value.trim();
        try {
            return LocalDate.parse(text).atTime(LocalTime.MAX);
        } catch (DateTimeParseException ignored) {
            return parseDateTime(text);
        }
    }

    private static LocalDateTime parseDateTime(String value) {
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(value, TIMESTAMP);
        } catch (DateTimeParseException e) {
            throw new PayrollException(
                    "时间格式错误：" + value + "，应为 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss");
        }
    }

    private static String resultValue(String value) {
        if (isBlank(value)) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!AuditEntry.SUCCESS.equals(normalized) && !AuditEntry.FAILURE.equals(normalized)) {
            throw new PayrollException("result 仅支持 SUCCESS 或 FAILURE");
        }
        return normalized;
    }

    private static String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String csv(List<AuditEntry> entries) {
        StringBuilder builder = new StringBuilder("\uFEFF");
        builder.append(
                "时间,操作人,动作,资源类型,资源ID,结果,状态码,来源IP,说明,变更前,变更后,请求体,TraceId\r\n");
        for (AuditEntry entry : entries) {
            appendRow(
                    builder,
                    entry.occurredAt() == null ? "" : entry.occurredAt().format(TIMESTAMP),
                    entry.actorName(),
                    entry.action(),
                    entry.resourceType(),
                    entry.resourceId(),
                    entry.result(),
                    entry.statusCode(),
                    entry.ip(),
                    entry.message(),
                    entry.beforeJson(),
                    entry.afterJson(),
                    entry.payloadJson(),
                    entry.traceId());
        }
        return builder.toString();
    }

    private static void appendRow(StringBuilder builder, Object... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(cell(values[i]));
        }
        builder.append("\r\n");
    }

    private static String cell(Object value) {
        if (value == null) {
            return "";
        }
        String text = String.valueOf(value);
        if (text.indexOf(',') < 0
                && text.indexOf('"') < 0
                && text.indexOf('\n') < 0
                && text.indexOf('\r') < 0) {
            return text;
        }
        return '"' + text.replace("\"", "\"\"") + '"';
    }
}
