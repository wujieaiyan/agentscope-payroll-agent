package io.agentscope.examples.payroll.profile;

import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PayrollNotFoundException;
import io.agentscope.examples.payroll.service.AuditService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 自助维护工作偏好：值域校验、缓存失效与哈希审计。
 *
 * <p>审计只落类别、键、值哈希和操作者，不落值原文；删除后无法从审计还原。
 */
public final class UserProfileAdminService {

    public static final String ACTION_CREATE = "profile.item.create";
    public static final String ACTION_UPDATE = "profile.item.update";
    public static final String ACTION_DELETE = "profile.item.delete";
    public static final String ACTION_CLEAR = "profile.clear";
    public static final String ACTION_PURGE = "profile.purge";

    private static final String RESOURCE_ITEM = "user_profile_item";
    private static final String RESOURCE_PROFILE = "user_profile";

    public record ItemView(
            long itemId,
            String category,
            String itemKey,
            String label,
            String itemValue,
            double confidence,
            String source,
            String sourceTurnId,
            String status,
            int injectedCount,
            LocalDateTime lastInjectedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {

        static ItemView of(UserProfileItem item) {
            return new ItemView(
                    item.itemId(),
                    item.category(),
                    item.itemKey(),
                    UserProfileTaxonomy.label(item.category(), item.itemKey()),
                    item.itemValue(),
                    item.confidence(),
                    item.source(),
                    item.sourceTurnId(),
                    item.status(),
                    item.injectedCount(),
                    item.lastInjectedAt(),
                    item.createdAt(),
                    item.updatedAt());
        }
    }

    public record KeyOption(
            String category, String key, String label, List<String> values) {}

    private final UserProfileStore store;
    private final AuditService audit;
    private final boolean enabled;

    public UserProfileAdminService(
            UserProfileStore store, AuditService audit, boolean enabled) {
        this.store = store;
        this.audit = audit;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public List<ItemView> list(long userId) {
        requireEnabled();
        List<ItemView> views = new ArrayList<>();
        for (UserProfileItem item : store.items(userId, true)) {
            views.add(ItemView.of(item));
        }
        return views;
    }

    /** 受控值域；前端手动新增时使用，避免自由文本进入画像。 */
    public List<KeyOption> options(long userId) {
        requireEnabled();
        List<String> departments = store.departments();
        List<String> cities = store.cities();
        List<KeyOption> options = new ArrayList<>();
        for (UserProfileTaxonomy.KeyDef def : UserProfileTaxonomy.keys()) {
            List<String> values =
                    switch (def.dictionary()) {
                        case CITY -> List.copyOf(cities);
                        case DEPARTMENT -> List.copyOf(departments);
                        case FIXED -> def.fixedValues();
                    };
            options.add(
                    new KeyOption(def.category(), def.key(), def.label(), values));
        }
        return options;
    }

    public ItemView create(
            long userId, String category, String key, String value) {
        requireEnabled();
        UserProfileTaxonomy.KeyDef def = requireKey(category, key);
        String canonical = requireValue(def, value);
        String evidence = "手动设置：" + canonical;
        UserProfileItem item =
                new UserProfileItem(
                        0L,
                        userId,
                        def.category(),
                        def.key(),
                        canonical,
                        1.0,
                        UserProfileItem.SOURCE_MANUAL,
                        null,
                        evidence,
                        UserProfileItem.STATUS_ACTIVE,
                        0,
                        null,
                        null,
                        null);
        UserProfileItem saved = store.upsert(item);
        ProfileMemory.invalidate(userId);
        audit.record(ACTION_CREATE, RESOURCE_ITEM, String.valueOf(saved.itemId()), null, snapshot(saved));
        return ItemView.of(saved);
    }

    /**
     * 修改值或启用/停用。值为空时只改状态；状态为空时沿用原状态。
     *
     * <p>写入一律标记为 MANUAL，避免用户主动设置的条目被后续抽取覆盖。
     */
    public ItemView update(long userId, long itemId, String value, String status) {
        requireEnabled();
        UserProfileItem existing = requireItem(userId, itemId);
        boolean valueProvided = value != null && !value.isBlank();
        if (!valueProvided && (status == null || status.isBlank())) {
            throw new PayrollException("请提供新的值或状态");
        }
        String nextStatus =
                status == null || status.isBlank()
                        ? (valueProvided ? UserProfileItem.STATUS_ACTIVE : existing.status())
                        : normalizeStatus(status);
        UserProfileTaxonomy.KeyDef def = requireKey(existing.category(), existing.itemKey());
        String nextValue =
                valueProvided ? requireValue(def, value) : existing.itemValue();
        UserProfileItem item =
                new UserProfileItem(
                        existing.itemId(),
                        userId,
                        def.category(),
                        def.key(),
                        nextValue,
                        1.0,
                        UserProfileItem.SOURCE_MANUAL,
                        existing.sourceTurnId(),
                        "手动设置：" + nextValue,
                        nextStatus,
                        0,
                        null,
                        existing.createdAt(),
                        null);
        UserProfileItem saved = store.upsert(item);
        ProfileMemory.invalidate(userId);
        audit.record(
                ACTION_UPDATE,
                RESOURCE_ITEM,
                String.valueOf(itemId),
                snapshot(existing),
                snapshot(saved));
        return ItemView.of(saved);
    }

    public void delete(long userId, long itemId) {
        requireEnabled();
        UserProfileItem existing = requireItem(userId, itemId);
        if (!store.delete(itemId, userId)) {
            throw new PayrollNotFoundException("偏好条目不存在：" + itemId);
        }
        ProfileMemory.invalidate(userId);
        audit.record(ACTION_DELETE, RESOURCE_ITEM, String.valueOf(itemId), snapshot(existing), null);
    }

    public int clear(long userId) {
        requireEnabled();
        int removed = store.deleteAll(userId);
        ProfileMemory.invalidate(userId);
        audit.record(
                ACTION_CLEAR,
                RESOURCE_PROFILE,
                String.valueOf(userId),
                Map.of("items", removed),
                Map.of("items", 0));
        return removed;
    }

    /** 合规删除：条目与抽取凭证一并物理删除。 */
    public int purge(long userId) {
        int removed = store.purge(userId);
        ProfileMemory.invalidate(userId);
        audit.record(
                ACTION_PURGE,
                RESOURCE_PROFILE,
                String.valueOf(userId),
                Map.of("rows", removed),
                Map.of("rows", 0));
        return removed;
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new PayrollException("工作偏好功能未启用");
        }
    }

    private UserProfileItem requireItem(long userId, long itemId) {
        return store.item(itemId, userId)
                .orElseThrow(() -> new PayrollNotFoundException("偏好条目不存在：" + itemId));
    }

    private UserProfileTaxonomy.KeyDef requireKey(String category, String key) {
        return UserProfileTaxonomy.find(category, key)
                .orElseThrow(
                        () ->
                                new PayrollException(
                                        "不支持的偏好类型：" + nullToEmpty(category) + "/" + nullToEmpty(key)));
    }

    private String requireValue(UserProfileTaxonomy.KeyDef def, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new PayrollException("请填写偏好值");
        }
        Optional<String> canonical =
                UserProfileTaxonomy.canonicalValue(
                        def.category(),
                        def.key(),
                        raw,
                        store.departments(),
                        store.cities());
        return canonical.orElseThrow(
                () ->
                        new PayrollException(
                                "偏好值不在允许范围：" + def.label() + " 只能取 "
                                        + String.join("、", allowedValues(def))));
    }

    private List<String> allowedValues(UserProfileTaxonomy.KeyDef def) {
        return switch (def.dictionary()) {
            case CITY -> List.copyOf(store.cities());
            case DEPARTMENT -> List.copyOf(store.departments());
            case FIXED -> def.fixedValues();
        };
    }

    private static String normalizeStatus(String status) {
        String normalized = status.trim().toUpperCase(java.util.Locale.ROOT);
        if (UserProfileItem.STATUS_ACTIVE.equals(normalized)
                || UserProfileItem.STATUS_DISABLED.equals(normalized)) {
            return normalized;
        }
        throw new PayrollException("状态只能为 ACTIVE 或 DISABLED");
    }

    /** 审计快照只含类别、键、值哈希与状态，不含值原文。 */
    private static Map<String, Object> snapshot(UserProfileItem item) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("category", item.category());
        snapshot.put("item_key", item.itemKey());
        snapshot.put("value_hash", hash(item.itemValue()));
        snapshot.put("status", item.status());
        snapshot.put("source", item.source());
        snapshot.put("item_id", item.itemId());
        return snapshot;
    }

    static String hash(String value) {
        if (value == null) {
            return "";
        }
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
