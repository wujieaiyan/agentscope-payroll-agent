package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.db.H2AuditStore;
import io.agentscope.examples.payroll.domain.AuditEntry;
import io.agentscope.examples.payroll.domain.AuditPage;
import io.agentscope.examples.payroll.domain.AuditQuery;
import io.agentscope.examples.payroll.support.AuditContext;
import io.agentscope.examples.payroll.support.AuditSanitizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 审计写入与查询。
 *
 * <p>写接口的成功/失败快照由控制器显式记录；没有显式记录的写请求由 {@code AuditFilter}
 * 补记通用事件。审计失败只告警，不影响业务请求。
 */
public final class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final int MAX_EXPORT = 10_000;
    private static final int EXPORT_BATCH = 500;

    private final H2AuditStore store;
    private final int retentionDays;

    public AuditService(H2AuditStore store, int retentionDays) {
        this.store = store;
        this.retentionDays = Math.max(1, retentionDays);
    }

    public int retentionDays() {
        return retentionDays;
    }

    /** 业务写操作成功后记录前后快照；请求失败时结果自动标记为 FAILURE。 */
    public void record(
            String action, String resourceType, String resourceId, Object before, Object after) {
        AuditContext.State context = AuditContext.current();
        String result =
                context != null && context.failureStatus() != null
                        ? AuditEntry.FAILURE
                        : AuditEntry.SUCCESS;
        persist(
                action,
                resourceType,
                resourceId,
                AuditSanitizer.json(before),
                AuditSanitizer.json(after),
                null,
                result,
                context == null ? null : context.failureStatus(),
                context == null ? null : context.failureMessage(),
                context);
    }

    /** 登录失败等无法提交业务快照的事件。 */
    public void recordFailure(
            String action, String resourceType, String resourceId, int statusCode, String message) {
        persist(
                action,
                resourceType,
                resourceId,
                null,
                null,
                null,
                AuditEntry.FAILURE,
                statusCode,
                message,
                AuditContext.current());
    }

    /** 过滤器兜底：请求未显式记录时补一条通用事件，并带上脱敏后的请求体。 */
    public void recordRequest(
            String method,
            String path,
            String query,
            String requestBody,
            int statusCode,
            String failureMessage) {
        AuditContext.State context = AuditContext.current();
        if (context != null && context.recorded()) {
            return;
        }
        String action = method.toLowerCase(Locale.ROOT) + " " + path;
        String resourceId = query == null || query.isBlank() ? path : path + "?" + query;
        persist(
                action,
                "endpoint",
                resourceId,
                null,
                null,
                AuditSanitizer.requestBody(requestBody),
                statusCode >= 400 ? AuditEntry.FAILURE : AuditEntry.SUCCESS,
                statusCode,
                failureMessage,
                context);
    }

    public AuditPage query(AuditQuery query) {
        return new AuditPage(store.find(query), store.count(query), query.page(), query.size());
    }

    /** 导出时按批拉取，最多 1 万条；条件与列表查询一致。 */
    public List<AuditEntry> export(AuditQuery query) {
        List<AuditEntry> all = new ArrayList<>();
        for (int page = 1; all.size() < MAX_EXPORT; page++) {
            List<AuditEntry> batch = store.find(query.withPage(page, EXPORT_BATCH));
            all.addAll(batch);
            if (batch.size() < EXPORT_BATCH) {
                break;
            }
        }
        return all.size() > MAX_EXPORT ? all.subList(0, MAX_EXPORT) : all;
    }

    public int purgeExpired() {
        return store.purgeBefore(LocalDateTime.now().minusDays(retentionDays));
    }

    private void persist(
            String action,
            String resourceType,
            String resourceId,
            String beforeJson,
            String afterJson,
            String payloadJson,
            String result,
            Integer statusCode,
            String message,
            AuditContext.State context) {
        Long actorId = context == null || context.actor() == null ? null : context.actor().id();
        String actorName = context == null ? null : context.actorName();
        AuditEntry entry =
                AuditEntry.pending(
                        actorId,
                        truncate(actorName, 64),
                        truncate(action, 128),
                        truncate(resourceType, 32),
                        truncate(resourceId, 256),
                        beforeJson,
                        afterJson,
                        payloadJson,
                        truncate(context == null ? null : context.ip(), 64),
                        truncate(context == null ? null : context.userAgent(), 256),
                        result,
                        statusCode,
                        truncate(message, 500),
                        context == null ? null : context.traceId());
        try {
            store.insert(entry);
        } catch (RuntimeException e) {
            log.warn("[审计] 写入失败：action={}，resource={}，原因={}", action, resourceId, e.getMessage());
        }
        AuditContext.markRecorded();
    }

    private static String truncate(String value, int limit) {
        if (value == null) {
            return null;
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}
