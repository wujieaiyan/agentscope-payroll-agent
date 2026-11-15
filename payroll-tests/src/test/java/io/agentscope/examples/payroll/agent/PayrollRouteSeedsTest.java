package io.agentscope.examples.payroll.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PayrollRouteSeedsTest {

    @Test
    @DisplayName("薪酬语义种子齐备：四桶 20/15/15/10，版本非空")
    void payrollCatalogIsComplete() {
        RouteIntentSeeds.Catalog catalog =
                RouteIntentSeeds.loadClasspath(
                        "/routing/payroll-route-intents.json", PayrollRouteBucket.ALL);

        assertFalse(catalog.version().isBlank());
        assertEquals(PayrollRouteBucket.ALL.size(), catalog.labels().size());
        assertEquals(20, catalog.count(PayrollRouteBucket.CALC));
        assertEquals(15, catalog.count(PayrollRouteBucket.QUERY));
        assertEquals(15, catalog.count(PayrollRouteBucket.POLICY));
        assertEquals(10, catalog.count(PayrollRouteBucket.ACTION));
        assertEquals(60, catalog.samples().size());
    }

    @Test
    @DisplayName("桶定义：只有计算/查询/政策可缓存，动作桶永不缓存")
    void onlyNonActionBucketsAreCacheable() {
        assertEquals(4, PayrollRouteBucket.ALL.size());
        assertEquals(3, PayrollRouteBucket.ALL.stream().filter(PayrollRouteBucket::cacheable).count());
        assertFalse(PayrollRouteBucket.cacheable(PayrollRouteBucket.ACTION));
        assertFalse(PayrollRouteBucket.cacheable(null));
        assertFalse(PayrollRouteBucket.known("payroll_other"));
    }

    @Test
    @DisplayName("档位解析：未知值按 off 处理")
    void modeParsingFallsBackToOff() {
        assertEquals(PayrollRouteMode.OFF, PayrollRouteMode.parse(null));
        assertEquals(PayrollRouteMode.OFF, PayrollRouteMode.parse(""));
        assertEquals(PayrollRouteMode.OFF, PayrollRouteMode.parse("on"));
        assertEquals(PayrollRouteMode.SHADOW, PayrollRouteMode.parse(" Shadow "));
        assertEquals(PayrollRouteMode.CACHE, PayrollRouteMode.parse("CACHE"));
        assertFalse(PayrollRouteMode.OFF.semanticEnabled());
        assertFalse(PayrollRouteMode.SHADOW.cacheEnabled());
        assertEquals(true, PayrollRouteMode.CACHE.cacheEnabled());
    }
}
