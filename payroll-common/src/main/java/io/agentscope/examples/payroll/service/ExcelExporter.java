package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.domain.PayrollResult;
import io.agentscope.examples.payroll.domain.PayrollRun;
import io.agentscope.examples.payroll.domain.PayrollRunFailure;
import io.agentscope.examples.payroll.domain.PayrollRunItem;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** 工资批次 Excel 导出：明细、核算失败与出账说明。 */
public final class ExcelExporter {

    public Path write(
            PayrollRun run,
            List<PayrollRunItem> items,
            List<PayrollRunFailure> failures,
            Path outputDir) {
        Path target =
                outputDir.toAbsolutePath()
                        .resolve("payroll-" + run.period() + "-run" + run.runId() + ".xlsx");
        try {
            Files.createDirectories(target.getParent());
            try (Workbook workbook = new XSSFWorkbook();
                    OutputStream out = Files.newOutputStream(target)) {
                Styles styles = new Styles(workbook);
                writeDetails(workbook, styles, run, items);
                writeFailures(workbook, styles, run, failures);
                writeNotes(workbook, run);
                workbook.write(out);
            }
            return target;
        } catch (IOException e) {
            throw new IllegalStateException("生成 Excel 失败：" + e.getMessage(), e);
        }
    }

    private void writeDetails(
            Workbook workbook,
            Styles styles,
            PayrollRun run,
            List<PayrollRunItem> items) {
        Sheet sheet = workbook.createSheet("工资明细");
        String[] headers = {
            "工号", "姓名", "部门", "城市", "期间", "基本工资", "岗位津贴", "加班费",
            "绩效奖金", "其他补贴", "应发工资", "考勤扣款", "个人社保", "个人公积金",
            "个人所得税", "扣款合计", "实发工资", "企业社保", "企业公积金", "企业成本",
            "邮箱", "邮箱状态", "发送失败原因", "风险提示"
        };
        Row header = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            Cell cell = header.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(styles.header);
        }

        int rowIndex = 1;
        for (PayrollRunItem item : items) {
            PayrollResult payroll = item.payroll();
            Row row = sheet.createRow(rowIndex++);
            text(row, 0, payroll.employeeId(), styles.text);
            text(row, 1, payroll.employeeName(), styles.text);
            text(row, 2, payroll.department(), styles.text);
            text(row, 3, item.city(), styles.text);
            text(row, 4, payroll.period(), styles.text);
            money(row, 5, payroll.baseSalary(), styles.money);
            money(row, 6, payroll.positionAllowance(), styles.money);
            money(row, 7, payroll.overtimePay(), styles.money);
            money(row, 8, payroll.performanceBonus(), styles.money);
            money(row, 9, payroll.otherAllowance(), styles.money);
            money(row, 10, payroll.grossPay(), styles.money);
            money(row, 11, payroll.attendanceDeduction(), styles.money);
            money(row, 12, payroll.socialInsuranceEmployee(), styles.money);
            money(row, 13, payroll.housingFundEmployee(), styles.money);
            money(row, 14, payroll.tax().taxThisMonth(), styles.money);
            money(row, 15, payroll.totalDeductions(), styles.money);
            money(row, 16, payroll.netPay(), styles.moneyStrong);
            money(row, 17, payroll.socialInsuranceEmployer(), styles.money);
            money(row, 18, payroll.housingFundEmployer(), styles.money);
            money(row, 19, payroll.employerCost(), styles.money);
            text(row, 20, item.email(), styles.text);
            text(row, 21, item.emailStatus(), styles.text);
            text(row, 22, item.emailReason(), styles.text);
            text(row, 23, String.join("；", payroll.warnings()), styles.text);
        }

        Row total = sheet.createRow(rowIndex);
        text(total, 0, "合计", styles.total);
        text(total, 1, "", styles.total);
        text(total, 2, "", styles.total);
        text(total, 3, "", styles.total);
        text(total, 4, "", styles.total);
        money(total, 5, sum(items, PayrollResult::baseSalary), styles.totalMoney);
        money(total, 6, sum(items, PayrollResult::positionAllowance), styles.totalMoney);
        money(total, 7, sum(items, PayrollResult::overtimePay), styles.totalMoney);
        money(total, 8, sum(items, PayrollResult::performanceBonus), styles.totalMoney);
        money(total, 9, sum(items, PayrollResult::otherAllowance), styles.totalMoney);
        money(total, 10, run.totalGross(), styles.totalMoney);
        money(total, 11, sum(items, PayrollResult::attendanceDeduction), styles.totalMoney);
        money(total, 12, sum(items, PayrollResult::socialInsuranceEmployee), styles.totalMoney);
        money(total, 13, sum(items, PayrollResult::housingFundEmployee), styles.totalMoney);
        money(
                total,
                14,
                sum(items, p -> p.tax().taxThisMonth()),
                styles.totalMoney);
        money(total, 15, run.totalDeductions(), styles.totalMoney);
        money(total, 16, run.totalNet(), styles.totalMoney);
        money(total, 17, sum(items, PayrollResult::socialInsuranceEmployer), styles.totalMoney);
        money(total, 18, sum(items, PayrollResult::housingFundEmployer), styles.totalMoney);
        money(total, 19, run.totalEmployerCost(), styles.totalMoney);

        for (int i = 0; i < headers.length; i++) {
            sheet.setColumnWidth(i, i >= 5 && i <= 19 ? 15 * 256 : 16 * 256);
        }
        sheet.setColumnWidth(23, 40 * 256);
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(
                new org.apache.poi.ss.util.CellRangeAddress(0, rowIndex, 0, headers.length - 1));
    }

    private void writeFailures(
            Workbook workbook,
            Styles styles,
            PayrollRun run,
            List<PayrollRunFailure> failures) {
        Sheet sheet = workbook.createSheet("核算失败");
        Row header = sheet.createRow(0);
        for (int i = 0; i < 2; i++) {
            Cell cell = header.createCell(i);
            cell.setCellStyle(styles.header);
        }
        header.getCell(0).setCellValue("工号");
        header.getCell(1).setCellValue("失败原因");
        int rowIndex = 1;
        for (PayrollRunFailure failure : failures) {
            Row row = sheet.createRow(rowIndex++);
            text(row, 0, failure.employeeId(), styles.text);
            text(row, 1, failure.message(), styles.text);
        }
        if (failures.isEmpty()) {
            Row row = sheet.createRow(1);
            text(row, 0, "-", styles.text);
            text(row, 1, "本批次没有核算失败的员工", styles.text);
        }
        sheet.setColumnWidth(0, 14 * 256);
        sheet.setColumnWidth(1, 80 * 256);
    }

    private void writeNotes(Workbook workbook, PayrollRun run) {
        Sheet sheet = workbook.createSheet("出账说明");
        String[][] lines = {
            {"批次号", String.valueOf(run.runId())},
            {"工资期间", run.period()},
            {"范围", run.scope()},
            {"批次状态", run.status()},
            {"人数", String.valueOf(run.employeeCount())},
            {"导出时间", LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))},
            {"金额口径", "所有金额由确定性核算引擎计算，累计预扣个税按年度累计口径"},
            {"邮件口径", "SIMULATED_SUCCESS 表示为 dry-run 模拟发送，未连接真实 SMTP，未真实投递"},
            {"数据来源", "H2 本地库：员工、考勤、考核、城市政策与浮动薪酬"}
        };
        for (int i = 0; i < lines.length; i++) {
            Row row = sheet.createRow(i);
            row.createCell(0).setCellValue(lines[i][0]);
            row.createCell(1).setCellValue(lines[i][1]);
        }
        sheet.setColumnWidth(0, 18 * 256);
        sheet.setColumnWidth(1, 90 * 256);
    }

    private static BigDecimal sum(
            List<PayrollRunItem> items,
            java.util.function.Function<PayrollResult, BigDecimal> extractor) {
        BigDecimal total = BigDecimal.ZERO;
        for (PayrollRunItem item : items) {
            total = total.add(extractor.apply(item.payroll()));
        }
        return total;
    }

    private static void text(Row row, int column, String value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    private static void money(Row row, int column, BigDecimal value, CellStyle style) {
        Cell cell = row.createCell(column);
        cell.setCellValue(value == null ? 0 : value.doubleValue());
        cell.setCellStyle(style);
    }

    private static final class Styles {
        private final CellStyle header;
        private final CellStyle text;
        private final CellStyle money;
        private final CellStyle moneyStrong;
        private final CellStyle total;
        private final CellStyle totalMoney;

        private Styles(Workbook workbook) {
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            header = workbook.createCellStyle();
            header.setFont(headerFont);
            header.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setAlignment(HorizontalAlignment.CENTER);
            header.setBorderBottom(BorderStyle.THIN);

            text = workbook.createCellStyle();
            text.setBorderBottom(BorderStyle.HAIR);

            money = workbook.createCellStyle();
            money.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));
            money.setBorderBottom(BorderStyle.HAIR);

            moneyStrong = workbook.createCellStyle();
            moneyStrong.cloneStyleFrom(money);
            Font bold = workbook.createFont();
            bold.setBold(true);
            moneyStrong.setFont(bold);

            Font totalFont = workbook.createFont();
            totalFont.setBold(true);
            total = workbook.createCellStyle();
            total.setFont(totalFont);
            total.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            total.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            total.setBorderTop(BorderStyle.THIN);

            totalMoney = workbook.createCellStyle();
            totalMoney.cloneStyleFrom(total);
            totalMoney.setDataFormat(workbook.createDataFormat().getFormat("#,##0.00"));
        }
    }
}
