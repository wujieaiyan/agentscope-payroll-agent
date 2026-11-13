package io.agentscope.examples.payroll.service;

import io.agentscope.examples.payroll.db.H2PayrollRepository;
import io.agentscope.examples.payroll.db.H2PayrollRunStore;
import io.agentscope.examples.payroll.db.PayrollDatabase;
import io.agentscope.examples.payroll.domain.PayrollPolicy;
import java.nio.file.Path;

/** 组装 H2 数据库、仓库、核算引擎与批次服务，供 CLI 与管理台共用。 */
public final class PayrollServices {

    private final PayrollDatabase database;
    private final H2PayrollRepository repository;
    private final PayrollEngine engine;
    private final PayrollRunService runService;
    private final PayrollChat chat;
    private final ExcelImportService excelImportService;

    private PayrollServices(
            PayrollDatabase database,
            H2PayrollRepository repository,
            PayrollEngine engine,
            PayrollRunService runService,
            PayrollChat chat,
            ExcelImportService excelImportService) {
        this.database = database;
        this.repository = repository;
        this.engine = engine;
        this.runService = runService;
        this.chat = chat;
        this.excelImportService = excelImportService;
    }

    /**
     * 测试或嵌入式场景：复用已初始化的数据库，并指定 Excel 输出目录。
     *
     * <p>对话实现由调用方显式注入；Web 后端不执行模型时传 {@code null}。
     */
    public static PayrollServices forDatabase(PayrollDatabase database, Path outputDir) {
        return forDatabase(database, outputDir, null);
    }

    /** 传入自定义对话实现，便于测试管理台接口而不依赖真实模型。 */
    public static PayrollServices forDatabase(
            PayrollDatabase database, Path outputDir, PayrollChat chat) {
        H2PayrollRepository repository = new H2PayrollRepository(database);
        PayrollPolicy defaultPolicy = repository.policyFor("默认");
        if (defaultPolicy == null) {
            throw new IllegalStateException("默认城市政策缺失，无法启动");
        }
        PayrollEngine engine = new PayrollEngine(repository, repository, defaultPolicy);
        PayrollRunService runService =
                new PayrollRunService(
                        engine,
                        repository,
                        new H2PayrollRunStore(database),
                        new ExcelExporter(),
                        new DryRunEmailGateway(),
                        outputDir);
        return of(
                database,
                repository,
                engine,
                runService,
                chat,
                new ExcelImportService(repository));
    }

    /** 由已装配的组件构建聚合对象，供 Spring 容器复用同一套实例。 */
    public static PayrollServices of(
            PayrollDatabase database,
            H2PayrollRepository repository,
            PayrollEngine engine,
            PayrollRunService runService,
            PayrollChat chat,
            ExcelImportService excelImportService) {
        return new PayrollServices(
                database, repository, engine, runService, chat, excelImportService);
    }

    public PayrollDatabase database() {
        return database;
    }

    public H2PayrollRepository repository() {
        return repository;
    }

    public PayrollEngine engine() {
        return engine;
    }

    public PayrollRunService runService() {
        return runService;
    }

    public PayrollChat chat() {
        return chat;
    }

    public ExcelImportService excelImportService() {
        return excelImportService;
    }

    public void close() {
        if (chat != null) {
            chat.close();
        }
    }
}
