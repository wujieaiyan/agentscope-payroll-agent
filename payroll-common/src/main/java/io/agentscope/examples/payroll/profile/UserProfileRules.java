package io.agentscope.examples.payroll.profile;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则层：第一人称的职责、默认范围与口径表达。值必须命中库内词典，否则丢弃；
 * 命中否定词的整句跳过，宁可少记也不把「我不负责华东区」记成偏好。
 */
public final class UserProfileRules {

    private static final List<String> NEGATIONS =
            List.of("不负责", "不是", "别按", "不要按", "不用按", "取消", "别用");
    private static final List<String> ROLE_TRIGGERS = List.of("负责", "管理", "对接", "分管", "主管");
    private static final List<String> SCOPE_TRIGGERS = List.of("默认", "以后", "平时", "一般", "通常");
    private static final List<String> TOPIC_TRIGGERS = List.of("关注", "关心", "重点", "主要看");

    public record Candidate(
            String category, String key, String value, double confidence, String evidence) {
    }

    private UserProfileRules() {
    }

    public static List<Candidate> match(
            String text, Collection<String> departments, Collection<String> cities) {
        if (text == null || text.isBlank() || containsAny(text, NEGATIONS)) {
            return List.of();
        }
        Map<String, Candidate> found = new LinkedHashMap<>();
        if (text.contains("我") && containsAny(text, ROLE_TRIGGERS)) {
            String city = firstMentioned(text, cities);
            if (city != null) {
                add(found, new Candidate(
                        UserProfileTaxonomy.CAT_ROLE,
                        UserProfileTaxonomy.KEY_REGION,
                        city,
                        0.9,
                        "规则命中：" + city));
            } else {
                String department = firstMentioned(text, departments);
                if (department != null) {
                    add(found, new Candidate(
                            UserProfileTaxonomy.CAT_ROLE,
                            UserProfileTaxonomy.KEY_DEPARTMENT,
                            department,
                            0.9,
                            "规则命中：" + department));
                }
            }
        }
        if (containsAny(text, SCOPE_TRIGGERS)) {
            String department = firstMentioned(text, departments);
            if (department != null) {
                add(found, new Candidate(
                        UserProfileTaxonomy.CAT_SCOPE,
                        UserProfileTaxonomy.KEY_DEFAULT_DEPARTMENT,
                        department,
                        0.8,
                        "规则命中：" + department));
            }
            String period = firstMentioned(text, fixedValues(UserProfileTaxonomy.KEY_DEFAULT_PERIOD));
            if (period != null) {
                add(found, new Candidate(
                        UserProfileTaxonomy.CAT_SCOPE,
                        UserProfileTaxonomy.KEY_DEFAULT_PERIOD,
                        period,
                        0.8,
                        "规则命中：" + period));
            }
        }
        if (text.contains("口径")) {
            String caliber = firstMentioned(text, fixedValues(UserProfileTaxonomy.KEY_CALIBER));
            if (caliber != null) {
                add(found, new Candidate(
                        UserProfileTaxonomy.CAT_PREFERENCE,
                        UserProfileTaxonomy.KEY_CALIBER,
                        caliber,
                        0.8,
                        "规则命中：" + caliber));
            }
        }
        if (containsAny(text, TOPIC_TRIGGERS)) {
            String focus = firstMentioned(text, fixedValues(UserProfileTaxonomy.KEY_FOCUS));
            if (focus != null) {
                add(found, new Candidate(
                        UserProfileTaxonomy.CAT_TOPIC,
                        UserProfileTaxonomy.KEY_FOCUS,
                        focus,
                        0.7,
                        "规则命中：" + focus));
            }
        }
        return new ArrayList<>(found.values());
    }

    private static void add(Map<String, Candidate> found, Candidate candidate) {
        found.put(candidate.category() + "|" + candidate.key(), candidate);
    }

    private static List<String> fixedValues(String key) {
        return UserProfileTaxonomy.keys().stream()
                .filter(def -> def.key().equals(key))
                .findFirst()
                .map(UserProfileTaxonomy.KeyDef::fixedValues)
                .orElse(List.of());
    }

    private static String firstMentioned(String text, Collection<String> values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank() && text.contains(value)) {
                return value;
            }
        }
        return null;
    }

    private static boolean containsAny(String text, Collection<String> words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
