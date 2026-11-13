package io.agentscope.examples.payroll.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.support.ToolTraceExtractor.Evidence;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ToolTraceExtractorTest {

    @Test
    @DisplayName("单人核算结果提取工号、期间与金额")
    void extractsSingleEmployeeResult() {
        String args = "{\"employee_id\":\"EMP001\",\"period\":\"2026-10\"}";
        String result =
                """
                {"status":"OK","data":{
                  "employee_id":"EMP001",
                  "name":"张伟",
                  "period":"2026-10",
                  "gross_pay":27340.00,
                  "total_deductions":6339.50,
                  "net_pay":21000.50
                }}
                """;

        Evidence evidence = ToolTraceExtractor.extract(args, result);

        assertEquals(List.of("EMP001"), evidence.employees());
        assertEquals("2026-10", evidence.period());
        assertNull(evidence.department());
        assertAmount("21000.50", evidence.amounts().get("net_pay"));
        assertAmount("27340.00", evidence.amounts().get("gross_pay"));
        assertEquals(3, evidence.amounts().size());
    }

    @Test
    @DisplayName("批量核算只取顶层合计，明细中的重复金额不进入金额表")
    void keepsOnlyBatchTotals() {
        String args = "{\"department\":\"研发部\",\"period\":\"2026-10\"}";
        String result =
                """
                {"status":"OK","data":{
                  "department":"研发部",
                  "period":"2026-10",
                  "count":2,
                  "totals":{"total_gross":50000.00,"total_net":41000.00},
                  "records":[
                    {"employee_id":"EMP001","gross_pay":27340.00,"net_pay":21000.50},
                    {"employee_id":"EMP002","gross_pay":22660.00,"net_pay":19999.50}
                  ]
                }}
                """;

        Evidence evidence = ToolTraceExtractor.extract(args, result);

        assertEquals(List.of("EMP001", "EMP002"), evidence.employees());
        assertEquals("研发部", evidence.department());
        assertAmount("50000.00", evidence.amounts().get("total_gross"));
        assertTrue(evidence.amounts().containsKey("total_net"));
        assertEquals(2, evidence.amounts().size());
    }

    @Test
    @DisplayName("员工清单结果只收集工号，不计入任何金额")
    void collectsEmployeeIdsFromList() {
        String result =
                """
                {"status":"OK","data":{"count":2,"employees":[
                  {"employee_id":"EMP001","name":"张伟","base_salary":20000.00},
                  {"employee_id":"EMP002","name":"李娜","base_salary":18000.00}
                ]}}
                """;

        Evidence evidence = ToolTraceExtractor.extract(null, result);

        assertEquals(List.of("EMP001", "EMP002"), evidence.employees());
        assertTrue(evidence.amounts().isEmpty());
    }

    @Test
    @DisplayName("错误结果与非法 JSON 不抛异常，返回空证据")
    void toleratesMalformedInput() {
        Evidence error = ToolTraceExtractor.extract("{bad json", "{\"status\":\"ERROR\"}");
        assertTrue(error.employees().isEmpty());
        assertTrue(error.amounts().isEmpty());
        assertNull(error.period());

        Evidence empty = ToolTraceExtractor.extract(null, null);
        assertEquals(Evidence.EMPTY, empty);
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertTrue(actual != null, "金额缺失：" + expected);
        assertEquals(0, new BigDecimal(expected).compareTo(actual), "金额不一致：" + actual);
    }
}
