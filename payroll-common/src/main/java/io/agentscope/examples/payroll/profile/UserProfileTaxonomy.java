package io.agentscope.examples.payroll.profile;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 固定词表与受控值域。类别和键不可由模型或用户自由扩展；值必须命中对应词典，
 * 否则整条丢弃，避免把一次性问题或自由文本写成长期偏好。
 */
public final class UserProfileTaxonomy {

    public static final String CAT_ROLE = "ROLE";
    public static final String CAT_SCOPE = "SCOPE";
    public static final String CAT_PREFERENCE = "PREFERENCE";
    public static final String CAT_TOPIC = "TOPIC";

    public static final String KEY_REGION = "region";
    public static final String KEY_DEPARTMENT = "department";
    public static final String KEY_DEFAULT_DEPARTMENT = "default_department";
    public static final String KEY_DEFAULT_PERIOD = "default_period";
    public static final String KEY_CALIBER = "caliber";
    public static final String KEY_FOCUS = "focus";

    public enum Dictionary {
        CITY,
        DEPARTMENT,
        FIXED
    }

    public record KeyDef(
            String category,
            String key,
            String label,
            Dictionary dictionary,
            List<String> fixedValues) {
    }

    private static final List<KeyDef> KEYS =
            List.of(
                    new KeyDef(
                            CAT_ROLE, KEY_REGION, "职责范围", Dictionary.CITY, List.of()),
                    new KeyDef(
                            CAT_ROLE, KEY_DEPARTMENT, "负责部门", Dictionary.DEPARTMENT, List.of()),
                    new KeyDef(
                            CAT_SCOPE,
                            KEY_DEFAULT_DEPARTMENT,
                            "常用部门",
                            Dictionary.DEPARTMENT,
                            List.of()),
                    new KeyDef(
                            CAT_SCOPE,
                            KEY_DEFAULT_PERIOD,
                            "默认周期",
                            Dictionary.FIXED,
                            List.of("本月", "上月", "本季度", "上季度", "本年")),
                    new KeyDef(
                            CAT_PREFERENCE,
                            KEY_CALIBER,
                            "口径偏好",
                            Dictionary.FIXED,
                            List.of("应发工资", "实发工资")),
                    new KeyDef(
                            CAT_TOPIC,
                            KEY_FOCUS,
                            "关注主题",
                            Dictionary.FIXED,
                            List.of("工资核算", "考勤管理", "绩效考核", "社保公积金", "个税")));

    private static final List<String> CATEGORIES =
            List.of(CAT_ROLE, CAT_SCOPE, CAT_PREFERENCE, CAT_TOPIC);

    private UserProfileTaxonomy() {
    }

    public static List<String> categories() {
        return CATEGORIES;
    }

    public static List<KeyDef> keys() {
        return KEYS;
    }

    public static Optional<KeyDef> find(String category, String key) {
        if (category == null || key == null) {
            return Optional.empty();
        }
        String normalizedCategory = category.trim().toUpperCase(Locale.ROOT);
        String normalizedKey = key.trim().toLowerCase(Locale.ROOT);
        return KEYS.stream()
                .filter(def ->
                        def.category().equals(normalizedCategory) && def.key().equals(normalizedKey))
                .findFirst();
    }

    public static String label(String category, String key) {
        return find(category, key)
                .map(KeyDef::label)
                .orElseGet(() -> category == null || category.isBlank() ? "偏好" : category.trim());
    }

    /** 类别优先级：ROLE > SCOPE > PREFERENCE > TOPIC；未知类别排到最后。 */
    public static int rank(String category) {
        int index = category == null ? -1 : CATEGORIES.indexOf(category.trim().toUpperCase(Locale.ROOT));
        return index < 0 ? CATEGORIES.size() : index;
    }

    public static String normalizeCategory(String category) {
        if (category == null) {
            return "";
        }
        String normalized = category.trim().toUpperCase(Locale.ROOT);
        return CATEGORIES.contains(normalized) ? normalized : "";
    }

    public static String normalizeKey(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 值是否在受控值域内：城市取 city_policies，部门取 departments，其余取固定枚举。
     * 比较时去空白并按不区分大小写匹配，入库仍保留词典原值。
     *
     * @return 命中的词典原值；不命中返回空
     */
    public static Optional<String> canonicalValue(
            String category,
            String key,
            String value,
            Collection<String> departments,
            Collection<String> cities) {
        Optional<KeyDef> def = find(category, key);
        if (def.isEmpty() || value == null || value.isBlank()) {
            return Optional.empty();
        }
        String candidate = value.trim();
        List<String> domain =
                switch (def.get().dictionary()) {
                    case CITY -> List.copyOf(cities);
                    case DEPARTMENT -> List.copyOf(departments);
                    case FIXED -> def.get().fixedValues();
                };
        return domain.stream()
                .filter(allowed -> allowed.equalsIgnoreCase(candidate))
                .findFirst();
    }

    /** 供抽取提示词使用的词表说明；值域随库内词典动态生成。 */
    public static String promptCatalog(Collection<String> departments, Collection<String> cities) {
        StringBuilder builder = new StringBuilder();
        for (KeyDef def : KEYS) {
            builder.append("- ").append(def.category()).append('/').append(def.key());
            builder.append("（").append(def.label()).append("）：");
            switch (def.dictionary()) {
                case CITY ->
                        builder.append("城市，必须来自：").append(String.join("、", cities));
                case DEPARTMENT ->
                        builder.append("部门，必须来自：").append(String.join("、", departments));
                case FIXED ->
                        builder.append("固定值，必须来自：")
                                .append(String.join("、", def.fixedValues()));
            }
            builder.append('\n');
        }
        return builder.toString();
    }
}
