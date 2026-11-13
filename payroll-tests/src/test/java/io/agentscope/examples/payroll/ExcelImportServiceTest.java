package io.agentscope.examples.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.AttendanceSummary;
import io.agentscope.examples.payroll.domain.EmployeeView;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.service.ExcelImportService;
import io.agentscope.examples.payroll.service.ExcelImportService.ImportReport;
import io.agentscope.examples.payroll.service.ExcelImportService.ImportType;
import io.agentscope.examples.payroll.service.PayrollServices;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.time.YearMonth;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ExcelImportServiceTest {

    private PayrollServices services;
    private ExcelImportService importer;

    @BeforeEach
    void setUp() throws IOException {
        PayrollDatabase database = PayrollDatabase.inMemory("import-" + UUID.randomUUID());
        services =
                PayrollServices.forDatabase(
                        database, Files.createTempDirectory("payroll-import-"));
        importer = services.excelImportService();
    }

    @AfterEach
    void tearDown() {
        services.close();
    }

    @Test
    @DisplayName("员工导入逐行校验并保留失败行行号与工号")
    void importsEmployeesAndReportsBadRows() {
        String[] headers = {
            "工号", "姓名", "部门", "岗位", "城市", "邮箱", "基本工资", "岗位津贴",
            "公积金比例", "社保基数", "专项附加扣除", "入职日期", "银行卡号"
        };
        Object[][] rows = {
            {
                "EMP007", "周杰", "研发部", "工程师", "北京", "zhoujie@example.com",
                20000, 1000, 0.12, null, 0, "2026-09-15", "6222 **** **** 1234"
            },
            {"EMP008", "", "销售部", "销售", "上海", "", 12000, 0, 0.12, null, 0, "2026-09-15", ""}
        };

        ImportReport report =
                importer.importWorkbook(ImportType.EMPLOYEES, ExcelTestWorkbooks.workbook(headers, rows));

        assertEquals(2, report.total());
        assertEquals(1, report.success());
        assertEquals(1, report.failed());
        assertEquals(3, report.errors().get(0).row());
        assertEquals("EMP008", report.errors().get(0).employeeId());
        assertTrue(report.errors().get(0).message().contains("姓名"));

        EmployeeView employee = services.repository().findEmployeeView("EMP007").orElseThrow();
        assertEquals("周杰", employee.name());
        assertEquals("北京", employee.city());
        assertEquals(0, new BigDecimal("20000").compareTo(employee.baseSalary()));
        assertEquals(
                0, new BigDecimal("0.12").compareTo(employee.housingFundRate()));
    }

    @Test
    @DisplayName("考核导入校验员工与系数，并写入绩效系数")
    void importsReviews() {
        String[] headers = {"工号", "期间", "考核分数", "绩效系数", "评语"};
        Object[][] rows = {
            {"EMP001", "2026-10", 95, 1.5, "超额完成"},
            {"EMP999", "2026-10", 90, 1.0, ""},
            {"EMP002", "2026/11", 80, 3.0, ""}
        };

        ImportReport report =
                importer.importWorkbook(ImportType.REVIEWS, ExcelTestWorkbooks.workbook(headers, rows));

        assertEquals(3, report.total());
        assertEquals(1, report.success());
        assertEquals(2, report.failed());
        assertTrue(
                report.errors().stream()
                        .anyMatch(error -> error.message().contains("员工不存在或已停用")));
        assertTrue(
                report.errors().stream()
                        .anyMatch(error -> error.message().contains("绩效系数必须在 0-2 之间")));
        assertEquals(
                0,
                new BigDecimal("1.5")
                        .compareTo(
                                services.repository()
                                        .performanceCoefficient(
                                                "EMP001", YearMonth.of(2026, 10))));
    }

    @Test
    @DisplayName("考勤导入覆盖已有考勤并拒绝负数")
    void importsAttendance() {
        String[] headers = {
            "工号", "期间", "事假天数", "病假天数", "旷工天数",
            "工作日加班小时", "休息日加班小时", "法定节假日加班小时"
        };
        Object[][] rows = {
            {"EMP001", "2026-10", 2, 1, 0, 10, 4, 0},
            {"EMP002", "2026-10", -1, 0, 0, 0, 0, 0}
        };

        ImportReport report =
                importer.importWorkbook(ImportType.ATTENDANCE, ExcelTestWorkbooks.workbook(headers, rows));

        assertEquals(2, report.total());
        assertEquals(1, report.success());
        assertEquals(1, report.failed());
        assertTrue(report.errors().get(0).message().contains("事假天数不能为负数"));

        AttendanceSummary attendance =
                services.repository()
                        .findAttendance("EMP001", YearMonth.of(2026, 10))
                        .orElseThrow();
        assertEquals(0, new BigDecimal("2").compareTo(attendance.personalLeaveDays()));
        assertEquals(0, new BigDecimal("1").compareTo(attendance.sickLeaveDays()));
        assertEquals(0, new BigDecimal("10").compareTo(attendance.weekdayOvertimeHours()));
        assertEquals(0, new BigDecimal("4").compareTo(attendance.restDayOvertimeHours()));
    }

    @Test
    @DisplayName("缺少必填列时拒绝整个文件并给出列名")
    void rejectsMissingRequiredColumns() {
        String[] headers = {"工号", "姓名", "部门"};

        PayrollException error =
                assertThrows(
                        PayrollException.class,
                        () ->
                                importer.importWorkbook(
                                        ImportType.EMPLOYEES,
                                        ExcelTestWorkbooks.workbook(
                                                headers,
                                                new Object[][] {{"EMP009", "测试", "研发部"}})));

        assertTrue(error.getMessage().contains("缺少必填列"));
        assertTrue(error.getMessage().contains("基本工资"));
        assertTrue(error.getMessage().contains("入职日期"));
    }

    @Test
    @DisplayName("模板包含标准表头与填写说明")
    void generatesTemplate() throws Exception {
        byte[] bytes = importer.template(ImportType.EMPLOYEES);

        assertNotNull(bytes);
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet data = workbook.getSheetAt(0);
            assertEquals("员工导入", data.getSheetName());
            assertEquals("工号", data.getRow(0).getCell(0).getStringCellValue());
            assertEquals("入职日期", data.getRow(0).getCell(11).getStringCellValue());
            assertEquals("填写说明", workbook.getSheetAt(1).getSheetName());
        }
    }
}
