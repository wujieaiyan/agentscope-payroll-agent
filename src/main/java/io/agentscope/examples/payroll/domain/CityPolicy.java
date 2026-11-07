package io.agentscope.examples.payroll.domain;

import java.util.Objects;

/** 某城市的社保/公积金政策快照，个税税率表沿用全国累计预扣口径。 */
public record CityPolicy(String city, PayrollPolicy policy, String updatedAt) {

    public CityPolicy {
        Objects.requireNonNull(city, "city");
        Objects.requireNonNull(policy, "policy");
        city = city.trim();
        if (city.isEmpty()) {
            throw new IllegalArgumentException("city must not be blank");
        }
    }
}
