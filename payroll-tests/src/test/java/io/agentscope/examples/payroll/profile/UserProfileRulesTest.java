package io.agentscope.examples.payroll.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class UserProfileRulesTest {

    private static final List<String> DEPARTMENTS = List.of("研发部", "销售部", "财务部");
    private static final List<String> CITIES = List.of("上海", "深圳", "北京");

    @Test
    @DisplayName("第一人称职责表达抽取城市与部门")
    void extractsRole() {
        List<UserProfileRules.Candidate> cities = match("我负责上海地区的工资核算");
        assertEquals(1, cities.size());
        assertEquals(UserProfileTaxonomy.CAT_ROLE, cities.get(0).category());
        assertEquals(UserProfileTaxonomy.KEY_REGION, cities.get(0).key());
        assertEquals("上海", cities.get(0).value());

        List<UserProfileRules.Candidate> departments = match("我负责研发部的考勤");
        assertEquals(1, departments.size());
        assertEquals(UserProfileTaxonomy.KEY_DEPARTMENT, departments.get(0).key());
        assertEquals("研发部", departments.get(0).value());
    }

    @Test
    @DisplayName("否定句整句跳过，避免把拒绝表达记成偏好")
    void skipsNegations() {
        assertTrue(match("我不负责上海地区").isEmpty());
        assertTrue(match("以后不要按研发部口径算").isEmpty());
        assertTrue(match("别按上海口径统计").isEmpty());
    }

    @Test
    @DisplayName("默认范围与周期取自固定枚举")
    void extractsScope() {
        List<UserProfileRules.Candidate> candidates = match("以后默认看研发部本月的数据");
        assertEquals(2, candidates.size());
        assertTrue(
                candidates.stream()
                        .anyMatch(c ->
                                UserProfileTaxonomy.KEY_DEFAULT_DEPARTMENT.equals(c.key())
                                        && "研发部".equals(c.value())));
        assertTrue(
                candidates.stream()
                        .anyMatch(c ->
                                UserProfileTaxonomy.KEY_DEFAULT_PERIOD.equals(c.key())
                                        && "本月".equals(c.value())));
    }

    @Test
    @DisplayName("口径偏好与关注主题只在命中固定词表时记录")
    void extractsPreferenceAndTopic() {
        List<UserProfileRules.Candidate> caliber = match("以后按应发工资口径统计");
        assertTrue(
                caliber.stream()
                        .anyMatch(c ->
                                UserProfileTaxonomy.CAT_PREFERENCE.equals(c.category())
                                        && "应发工资".equals(c.value())));

        List<UserProfileRules.Candidate> topic = match("我比较关注个税申报");
        assertEquals(1, topic.size());
        assertEquals(UserProfileTaxonomy.CAT_TOPIC, topic.get(0).category());
        assertEquals(UserProfileTaxonomy.KEY_FOCUS, topic.get(0).key());
        assertEquals("个税", topic.get(0).value());
    }

    @Test
    @DisplayName("词表外的值一律丢弃，不写入自由文本")
    void dropsUnknownValues() {
        assertTrue(match("我负责火星基地").isEmpty());
        assertTrue(match("我比较关注火箭发射").isEmpty());
        assertTrue(match("以后默认看不存在部").isEmpty());
        assertTrue(match("以后按随机口径统计").isEmpty());
    }

    @Test
    @DisplayName("一句话可同时命中多个类别")
    void extractsMultipleCategories() {
        List<UserProfileRules.Candidate> candidates = match("我负责上海，也比较关注个税");
        assertEquals(2, candidates.size());
        assertTrue(
                candidates.stream()
                        .anyMatch(c ->
                                UserProfileTaxonomy.CAT_ROLE.equals(c.category())
                                        && "上海".equals(c.value())));
        assertTrue(
                candidates.stream()
                        .anyMatch(c ->
                                UserProfileTaxonomy.CAT_TOPIC.equals(c.category())
                                        && "个税".equals(c.value())));
    }

    private List<UserProfileRules.Candidate> match(String text) {
        return UserProfileRules.match(text, DEPARTMENTS, CITIES);
    }
}
