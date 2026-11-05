package io.agentscope.examples.payroll.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.regex.Pattern;

/** 月度考勤汇总，天数与小时数均由考勤系统给定，智能体不得自行推算。 */
public record AttendanceSummary(
        @JsonProperty("employee_id") String employeeId,
        String period,
        @JsonProperty("personal_leave_days") BigDecimal personalLeaveDays,
        @JsonProperty("sick_leave_days") BigDecimal sickLeaveDays,
        @JsonProperty("absent_days") BigDecimal absentDays,
        @JsonProperty("weekday_overtime_hours") BigDecimal weekdayOvertimeHours,
        @JsonProperty("rest_day_overtime_hours") BigDecimal restDayOvertimeHours,
        @JsonProperty("holiday_overtime_hours") BigDecimal holidayOvertimeHours) {

    private static final Pattern PERIOD_PATTERN = Pattern.compile("\\d{4}-(0[1-9]|1[0-2])");

    public AttendanceSummary {
        if (period == null || !PERIOD_PATTERN.matcher(period).matches()) {
            throw new IllegalArgumentException("period must be YYYY-MM: " + period);
        }
        personalLeaveDays = nonNegative(personalLeaveDays, "personalLeaveDays");
        sickLeaveDays = nonNegative(sickLeaveDays, "sickLeaveDays");
        absentDays = nonNegative(absentDays, "absentDays");
        weekdayOvertimeHours = nonNegative(weekdayOvertimeHours, "weekdayOvertimeHours");
        restDayOvertimeHours = nonNegative(restDayOvertimeHours, "restDayOvertimeHours");
        holidayOvertimeHours = nonNegative(holidayOvertimeHours, "holidayOvertimeHours");
    }

    public static AttendanceSummary fullMonth(String employeeId, YearMonth period) {
        return new AttendanceSummary(
                employeeId,
                period.toString(),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO);
    }

    public BigDecimal totalLeaveDays() {
        return personalLeaveDays.add(sickLeaveDays).add(absentDays);
    }

    private static BigDecimal nonNegative(BigDecimal value, String field) {
        BigDecimal v = Money.nz(value);
        if (v.signum() < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
        return v;
    }
}
