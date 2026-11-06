package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.db.H2PayrollRepository;
import io.agentscope.examples.payroll.domain.AttendanceSummary;
import io.agentscope.examples.payroll.domain.Employee;
import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.domain.PerformanceReview;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * 员工、考核、考勤的 Excel 导入与模板导出。
 *
 * <p>按表头名称匹配字段，逐行校验并入库；单行失败不影响其他行，
 * 返回行号、工号与原因供 HR 修正后重试。
 */
public final class ExcelImportService {

    public enum ImportType {
        EMPLOYEES("employees", "员工", "员工导入"),
        REVIEWS("reviews", "考核", "考核导入"),
        ATTENDANCE("attendance", "考勤", "考勤导入");

        private final String key;
        private final String label;
        private final String sheetName;

        ImportType(String key, String label, String sheetName) {
            this.key = key;
            this.label = label;
            this.sheetName = sheetName;
        }

        public String key() {
            return key;
        }

        public String label() {
            return label;
        }

        public String sheetName() {
            return sheetName;
        }

        public static ImportType fromPath(String path) {
            for (ImportType type : values()) {
                if (type.key.equalsIgnoreCase(path)) {
                    return type;
                }
            }
            throw new PayrollException(
                    "不支持的导入类型：" + path + "，可选 employees / reviews / attendance");
        }
    }

    public record ImportError(int row, String employeeId, String message) {
    }

    public record ImportReport(
            String type,
            String typeLabel,
            int total,
            int success,
            int failed,
            List<ImportError> errors) {
    }

    private record Column(String key, String header, List<String> aliases) {
    }

    private static final int MAX_ROWS = 5000;
    private static final BigDecimal DEFAULT_HOUSING_FUND_RATE = new BigDecimal("0.12");
    private static final List<DateTimeFormatter> DATE_FORMATS =
            List.of(
                    DateTimeFormatter.ofPattern("yyyy-M-d"),
                    DateTimeFormatter.ofPattern("yyyy/M/d"),
                    DateTimeFormatter.ofPattern("yyyy.M.d"),
                    DateTimeFormatter.ofPattern("yyyyMMdd"));

    private final H2PayrollRepository repository;

    public ExcelImportService(H2PayrollRepository repository) {
        this.repository = repository;
    }

    public ImportReport importWorkbook(ImportType type, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new PayrollException("请上传 Excel 文件");
        }
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new PayrollException("Excel 中没有工作表");
            }
            Sheet sheet = workbook.getSheetAt(0);
            List<Column> columns = columns(type);
            int headerRowIndex = findHeaderRow(sheet, columns);
            Row headerRow = sheet.getRow(headerRowIndex);
            Map<String, Integer> indexes = mapColumns(headerRow, columns);
            requireHeaders(type, columns, indexes, headerRow);

            List<ImportError> errors = new ArrayList<>();
            int total = 0;
            for (int rowIndex = headerRowIndex + 1;
                    rowIndex <= sheet.getLastRowNum();
                    rowIndex++) {
                Row row = sheet.getRow(rowIndex);
                if (row == null || isBlank(row, indexes)) {
                    continue;
                }
                total++;
                if (total > MAX_ROWS) {
                    throw new PayrollException("单次最多导入 " + MAX_ROWS + " 行，请拆分文件");
                }
                String employeeId = "";
                try {
                    employeeId =
                            text(cell(row, indexes, "employee_id"))
                                    .trim()
                                    .toUpperCase(Locale.ROOT);
                    switch (type) {
                        case EMPLOYEES -> importEmployee(row, indexes);
                        case REVIEWS -> importReview(row, indexes);
                        case ATTENDANCE -> importAttendance(row, indexes);
                    }
                } catch (Exception e) {
                    errors.add(new ImportError(rowIndex + 1, employeeId, message(e)));
                }
            }
            return new ImportReport(
                    type.key(),
                    type.label(),
                    total,
                    total - errors.size(),
                    errors.size(),
                    List.copyOf(errors));
        } catch (PayrollException e) {
            throw e;
        } catch (Exception e) {
            throw new PayrollException("无法解析 Excel：" + message(e), e);
        }
    }

    public byte[] template(ImportType type) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            List<Column> columns = columns(type);
            Sheet sheet = workbook.createSheet(type.sheetName());
            writeHeader(workbook, sheet, columns);
            writeInstructions(workbook, type);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("生成导入模板失败：" + e.getMessage(), e);
        }
    }

    private void importEmployee(Row row, Map<String, Integer> indexes) {
        String employeeId = requiredText(row, indexes, "employee_id");
        String name = requiredText(row, indexes, "name");
        String department = requiredText(row, indexes, "department");
        String position = optionalText(row, indexes, "position");
        String city = optionalText(row, indexes, "city");
        String email = optionalText(row, indexes, "email");
        String bankAccount = optionalText(row, indexes, "bank_account_masked");
        BigDecimal baseSalary = nonNegative(requiredDecimal(row, indexes, "base_salary"), "基本工资");
        BigDecimal positionAllowance =
                nonNegative(optionalDecimal(row, indexes, "position_allowance", BigDecimal.ZERO), "岗位津贴");
        BigDecimal housingFundRate = housingFundRate(row, indexes);
        BigDecimal socialInsuranceBase = optionalDecimal(row, indexes, "social_insurance_base", null);
        if (socialInsuranceBase != null) {
            nonNegative(socialInsuranceBase, "社保基数");
        }
        BigDecimal specialAdditionalDeduction =
                nonNegative(
                        optionalDecimal(
                                row, indexes, "special_additional_deduction", BigDecimal.ZERO),
                        "专项附加扣除");
        LocalDate hireDate = requiredDate(row, indexes, "hire_date");

        repository.saveEmployee(
                new Employee(
                        employeeId,
                        name,
                        department,
                        position,
                        baseSalary,
                        positionAllowance,
                        housingFundRate,
                        socialInsuranceBase,
                        specialAdditionalDeduction,
                        hireDate,
                        bankAccount,
                        email,
                        city.isBlank() ? "默认" : city));
    }

    private void importReview(Row row, Map<String, Integer> indexes) {
        String employeeId = requiredText(row, indexes, "employee_id");
        requireActiveEmployee(employeeId);
        String period = requiredPeriod(row, indexes, "period");
        BigDecimal score = requiredDecimal(row, indexes, "score");
        BigDecimal coefficient =
                optionalDecimal(row, indexes, "coefficient", BigDecimal.ONE);
        String comment = optionalText(row, indexes, "comment");
        repository.saveReview(
                new PerformanceReview(employeeId, period, score, coefficient, comment));
    }

    private void importAttendance(Row row, Map<String, Integer> indexes) {
        String employeeId = requiredText(row, indexes, "employee_id");
        requireActiveEmployee(employeeId);
        String period = requiredPeriod(row, indexes, "period");
        BigDecimal personalLeave =
                nonNegative(optionalDecimal(row, indexes, "personal_leave_days", BigDecimal.ZERO), "事假天数");
        BigDecimal sickLeave =
                nonNegative(optionalDecimal(row, indexes, "sick_leave_days", BigDecimal.ZERO), "病假天数");
        BigDecimal absent =
                nonNegative(optionalDecimal(row, indexes, "absent_days", BigDecimal.ZERO), "旷工天数");
        BigDecimal weekdayOvertime =
                nonNegative(
                        optionalDecimal(row, indexes, "weekday_overtime_hours", BigDecimal.ZERO),
                        "工作日加班小时");
        BigDecimal restDayOvertime =
                nonNegative(
                        optionalDecimal(row, indexes, "rest_day_overtime_hours", BigDecimal.ZERO),
                        "休息日加班小时");
        BigDecimal holidayOvertime =
                nonNegative(
                        optionalDecimal(row, indexes, "holiday_overtime_hours", BigDecimal.ZERO),
                        "法定节假日加班小时");
        repository.saveAttendance(
                new AttendanceSummary(
                        employeeId,
                        period,
                        personalLeave,
                        sickLeave,
                        absent,
                        weekdayOvertime,
                        restDayOvertime,
                        holidayOvertime));
    }

    private void requireActiveEmployee(String employeeId) {
        if (repository.findEmployee(employeeId).isEmpty()) {
            throw new PayrollException("员工不存在或已停用：" + employeeId);
        }
    }

    private static void writeHeader(Workbook workbook, Sheet sheet, List<Column> columns) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        font.setColor(IndexedColors.WHITE.getIndex());
        style.setFont(font);
        style.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setAlignment(HorizontalAlignment.CENTER);

        Row row = sheet.createRow(0);
        row.setHeightInPoints(22);
        for (int i = 0; i < columns.size(); i++) {
            Cell cell = row.createCell(i);
            cell.setCellValue(columns.get(i).header());
            cell.setCellStyle(style);
            int width = Math.max(12, columns.get(i).header().length() * 3 + 6);
            sheet.setColumnWidth(i, Math.min(width, 30) * 256);
        }
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, columns.size() - 1));
    }

    private static void writeInstructions(Workbook workbook, ImportType type) {
        Sheet sheet = workbook.createSheet("填写说明");
        CellStyle wrap = workbook.createCellStyle();
        wrap.setWrapText(true);
        sheet.setColumnWidth(0, 110 * 256);

        List<String> rules =
                new ArrayList<>(
                        List.of(
                                "1. 第一行为表头，请勿修改列名；支持 .xlsx 和 .xls 文件。",
                                "2. 工号必填；员工导入为整行覆盖，已存在的工号会更新档案并重新启用。",
                                "3. 金额单位为元；日期格式为 yyyy-MM-dd；公积金比例可填 0.12 或 12%。",
                                "4. 单次最多导入 " + MAX_ROWS + " 行，完全空白的行会自动跳过。",
                                "5. 导入结果会列出成功数、失败数，以及每个失败行的行号、工号和原因。"));
        switch (type) {
            case EMPLOYEES ->
                    rules.add(
                            "6. 员工必填：工号、姓名、部门、基本工资、入职日期；"
                                    + "城市为空按“默认”政策，社保基数可留空。"
                                    + "示例：EMP007 / 周杰 / 研发部 / 工程师 / 北京 / "
                                    + "zhoujie@example.com / 20000 / 1000 / 0.12 / 空 / 0 / "
                                    + "2026-10-01 / 6222 **** **** 1234");
            case REVIEWS ->
                    rules.add(
                            "6. 考核必填：工号、期间、考核分数；绩效系数留空按 1 处理，"
                                    + "分数范围 0-100，系数范围 0-2。");
            case ATTENDANCE ->
                    rules.add(
                            "6. 考勤必填：工号、期间；天数和小时数留空按 0 处理，"
                                    + "同一工号同一期间会覆盖已有考勤。");
        }
        for (int i = 0; i < rules.size(); i++) {
            Row row = sheet.createRow(i);
            Cell cell = row.createCell(0);
            cell.setCellValue(rules.get(i));
            cell.setCellStyle(wrap);
            row.setHeightInPoints(30);
        }
    }

    private static int findHeaderRow(Sheet sheet, List<Column> columns) {
        int bestRow = -1;
        int bestScore = 0;
        int last = Math.min(sheet.getLastRowNum(), 9);
        for (int rowIndex = 0; rowIndex <= last; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null) {
                continue;
            }
            int score = 0;
            int lastCell = Math.max(0, row.getLastCellNum());
            for (int cellIndex = 0; cellIndex < lastCell; cellIndex++) {
                if (columnFor(columns, text(row.getCell(cellIndex))) != null) {
                    score++;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestRow = rowIndex;
            }
        }
        if (bestRow < 0 || bestScore < 2) {
            throw new PayrollException(
                    "未识别到有效表头，请使用管理台下载的模板，或确保第一行包含“工号”等列名");
        }
        return bestRow;
    }

    private static Map<String, Integer> mapColumns(Row headerRow, List<Column> columns) {
        Map<String, Integer> indexes = new LinkedHashMap<>();
        int lastCell = Math.max(0, headerRow.getLastCellNum());
        for (int cellIndex = 0; cellIndex < lastCell; cellIndex++) {
            Column column = columnFor(columns, text(headerRow.getCell(cellIndex)));
            if (column != null) {
                indexes.putIfAbsent(column.key(), cellIndex);
            }
        }
        return indexes;
    }

    private static void requireHeaders(
            ImportType type, List<Column> columns, Map<String, Integer> indexes, Row headerRow) {
        Set<String> required = requiredKeys(type);
        List<String> missing =
                columns.stream()
                        .filter(column -> required.contains(column.key()))
                        .filter(column -> !indexes.containsKey(column.key()))
                        .map(Column::header)
                        .toList();
        if (!missing.isEmpty()) {
            throw new PayrollException(
                    "Excel 缺少必填列："
                            + String.join("、", missing)
                            + "。当前表头："
                            + headerText(headerRow));
        }
    }

    private static String headerText(Row headerRow) {
        int lastCell = Math.max(0, headerRow.getLastCellNum());
        List<String> headers = new ArrayList<>();
        for (int cellIndex = 0; cellIndex < lastCell; cellIndex++) {
            String value = text(headerRow.getCell(cellIndex));
            if (!value.isBlank()) {
                headers.add(value);
            }
        }
        return headers.isEmpty() ? "（空）" : String.join(" / ", headers);
    }

    private static Set<String> requiredKeys(ImportType type) {
        return switch (type) {
            case EMPLOYEES -> Set.of("employee_id", "name", "department", "base_salary", "hire_date");
            case REVIEWS -> Set.of("employee_id", "period", "score");
            case ATTENDANCE -> Set.of("employee_id", "period");
        };
    }

    private static List<Column> columns(ImportType type) {
        return switch (type) {
            case EMPLOYEES ->
                    List.of(
                            column("employee_id", "工号", "员工编号", "employeeid"),
                            column("name", "姓名"),
                            column("department", "部门"),
                            column("position", "岗位", "岗位名称", "职位"),
                            column("city", "城市"),
                            column("email", "邮箱", "邮件", "email"),
                            column("base_salary", "基本工资", "月基本工资", "basesalary"),
                            column("position_allowance", "岗位津贴", "positionallowance"),
                            column("housing_fund_rate", "公积金比例", "公积金率", "housingfundrate"),
                            column("social_insurance_base", "社保基数", "社保缴费基数"),
                            column("special_additional_deduction", "专项附加扣除", "专项扣除"),
                            column("hire_date", "入职日期", "入职时间", "hiredate"),
                            column("bank_account_masked", "银行卡号", "银行卡", "工资卡"));
            case REVIEWS ->
                    List.of(
                            column("employee_id", "工号", "员工编号", "employeeid"),
                            column("period", "期间", "工资期间", "period"),
                            column("score", "考核分数", "分数", "score"),
                            column("coefficient", "绩效系数", "系数", "coefficient"),
                            column("comment", "评语", "备注", "comment"));
            case ATTENDANCE ->
                    List.of(
                            column("employee_id", "工号", "员工编号", "employeeid"),
                            column("period", "期间", "工资期间", "period"),
                            column("personal_leave_days", "事假天数", "事假"),
                            column("sick_leave_days", "病假天数", "病假"),
                            column("absent_days", "旷工天数", "旷工"),
                            column("weekday_overtime_hours", "工作日加班小时", "工作日加班"),
                            column("rest_day_overtime_hours", "休息日加班小时", "休息日加班"),
                            column("holiday_overtime_hours", "法定节假日加班小时", "节假日加班", "法定节假日加班"));
        };
    }

    private static Column column(String key, String header, String... aliases) {
        List<String> all = new ArrayList<>();
        all.add(header);
        all.addAll(List.of(aliases));
        return new Column(key, header, all);
    }

    private static Column columnFor(List<Column> columns, String header) {
        String normalized = normalize(header);
        if (normalized.isEmpty()) {
            return null;
        }
        for (Column column : columns) {
            for (String alias : column.aliases()) {
                if (normalize(alias).equals(normalized)) {
                    return column;
                }
            }
        }
        return null;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[（(].*?[)）]", "")
                .replaceAll("[\\s_\\-/:：]", "");
    }

    private static boolean isBlank(Row row, Map<String, Integer> indexes) {
        for (Integer index : indexes.values()) {
            if (!text(row.getCell(index)).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static Cell cell(Row row, Map<String, Integer> indexes, String key) {
        Integer index = indexes.get(key);
        return index == null ? null : row.getCell(index);
    }

    private static String requiredText(Row row, Map<String, Integer> indexes, String key) {
        String value = optionalText(row, indexes, key);
        if (value.isBlank()) {
            throw new PayrollException("缺少必填值：" + headerOf(key));
        }
        return value;
    }

    private static String optionalText(Row row, Map<String, Integer> indexes, String key) {
        return text(cell(row, indexes, key)).trim();
    }

    private static BigDecimal requiredDecimal(Row row, Map<String, Integer> indexes, String key) {
        BigDecimal value = decimalCell(cell(row, indexes, key), headerOf(key));
        if (value == null) {
            throw new PayrollException("缺少必填值：" + headerOf(key));
        }
        return value;
    }

    private static BigDecimal optionalDecimal(
            Row row, Map<String, Integer> indexes, String key, BigDecimal fallback) {
        BigDecimal value = decimalCell(cell(row, indexes, key), headerOf(key));
        return value == null ? fallback : value;
    }

    private static BigDecimal housingFundRate(Row row, Map<String, Integer> indexes) {
        BigDecimal value = decimalCell(cell(row, indexes, "housing_fund_rate"), "公积金比例");
        if (value == null) {
            return DEFAULT_HOUSING_FUND_RATE;
        }
        if (value.compareTo(BigDecimal.ONE) > 0) {
            value = value.movePointLeft(2);
        }
        if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new PayrollException("公积金比例必须在 0-1 之间，可填 0.12 或 12%");
        }
        return value;
    }

    private static BigDecimal nonNegative(BigDecimal value, String label) {
        if (value != null && value.signum() < 0) {
            throw new PayrollException(label + "不能为负数");
        }
        return value;
    }

    private static LocalDate requiredDate(Row row, Map<String, Integer> indexes, String key) {
        Cell cell = cell(row, indexes, key);
        if (cell == null || text(cell).isBlank()) {
            throw new PayrollException("缺少必填值：" + headerOf(key));
        }
        CellType type = effectiveType(cell);
        if (type == CellType.NUMERIC) {
            double numeric = cell.getNumericCellValue();
            if (numeric >= 10000101 && numeric <= 99991231 && numeric == Math.rint(numeric)) {
                int value = (int) numeric;
                return LocalDate.of(value / 10000, (value / 100) % 100, value % 100);
            }
            return cell.getLocalDateTimeCellValue().toLocalDate();
        }
        String raw = text(cell).trim();
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException ignored) {
            for (DateTimeFormatter formatter : DATE_FORMATS) {
                try {
                    return LocalDate.parse(raw, formatter);
                } catch (DateTimeParseException ignoredAgain) {
                    // try next format
                }
            }
            throw new PayrollException("日期格式错误：" + raw + "，应为 yyyy-MM-dd");
        }
    }

    private static String requiredPeriod(Row row, Map<String, Integer> indexes, String key) {
        Cell cell = cell(row, indexes, key);
        if (cell == null || text(cell).isBlank()) {
            throw new PayrollException("缺少必填值：" + headerOf(key));
        }
        if (effectiveType(cell) == CellType.NUMERIC) {
            double numeric = cell.getNumericCellValue();
            if (numeric >= 190001 && numeric <= 999912 && numeric == Math.rint(numeric)) {
                int value = (int) numeric;
                return YearMonth.of(value / 100, value % 100).toString();
            }
            return YearMonth.from(cell.getLocalDateTimeCellValue()).toString();
        }
        String raw =
                text(cell)
                        .trim()
                        .replace("年", "-")
                        .replace("月", "")
                        .replace('/', '-')
                        .replace('.', '-')
                        .replaceAll("-+$", "");
        if (raw.matches("\\d{6}")) {
            raw = raw.substring(0, 4) + "-" + raw.substring(4);
        }
        try {
            return YearMonth.parse(raw).toString();
        } catch (DateTimeParseException e) {
            throw new PayrollException("期间格式错误：" + text(cell) + "，应为 YYYY-MM");
        }
    }

    private static BigDecimal decimalCell(Cell cell, String label) {
        if (cell == null) {
            return null;
        }
        CellType type = effectiveType(cell);
        if (type == CellType.NUMERIC) {
            return BigDecimal.valueOf(cell.getNumericCellValue());
        }
        if (type == CellType.BLANK) {
            return null;
        }
        if (type != CellType.STRING) {
            return null;
        }
        String raw = cell.getStringCellValue().trim();
        if (raw.isEmpty()) {
            return null;
        }
        boolean percent = raw.endsWith("%") || raw.endsWith("％");
        String normalized =
                raw.replace("%", "")
                        .replace("％", "")
                        .replace(",", "")
                        .replace("¥", "")
                        .replace("￥", "")
                        .replace("元", "")
                        .trim();
        try {
            BigDecimal value = new BigDecimal(normalized);
            return percent ? value.movePointLeft(2) : value;
        } catch (NumberFormatException e) {
            throw new PayrollException(label + "格式不正确：" + raw);
        }
    }

    private static String text(Cell cell) {
        if (cell == null) {
            return "";
        }
        CellType type = effectiveType(cell);
        return switch (type) {
            case STRING -> cell.getStringCellValue().trim();
            case NUMERIC ->
                    DateUtil.isCellDateFormatted(cell)
                            ? cell.getLocalDateTimeCellValue().toLocalDate().toString()
                            : BigDecimal.valueOf(cell.getNumericCellValue())
                                    .stripTrailingZeros()
                                    .toPlainString();
            case BOOLEAN -> Boolean.toString(cell.getBooleanCellValue());
            default -> "";
        };
    }

    private static CellType effectiveType(Cell cell) {
        return cell.getCellType() == CellType.FORMULA
                ? cell.getCachedFormulaResultType()
                : cell.getCellType();
    }

    private static String headerOf(String key) {
        return switch (key) {
            case "employee_id" -> "工号";
            case "name" -> "姓名";
            case "department" -> "部门";
            case "position" -> "岗位";
            case "city" -> "城市";
            case "email" -> "邮箱";
            case "base_salary" -> "基本工资";
            case "position_allowance" -> "岗位津贴";
            case "housing_fund_rate" -> "公积金比例";
            case "social_insurance_base" -> "社保基数";
            case "special_additional_deduction" -> "专项附加扣除";
            case "hire_date" -> "入职日期";
            case "bank_account_masked" -> "银行卡号";
            case "period" -> "期间";
            case "score" -> "考核分数";
            case "coefficient" -> "绩效系数";
            case "comment" -> "评语";
            case "personal_leave_days" -> "事假天数";
            case "sick_leave_days" -> "病假天数";
            case "absent_days" -> "旷工天数";
            case "weekday_overtime_hours" -> "工作日加班小时";
            case "rest_day_overtime_hours" -> "休息日加班小时";
            case "holiday_overtime_hours" -> "法定节假日加班小时";
            default -> key;
        };
    }

    private static String message(Throwable error) {
        Throwable current = error;
        String message = null;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            current = current.getCause();
        }
        return message == null ? error.getClass().getSimpleName() : message;
    }
}
