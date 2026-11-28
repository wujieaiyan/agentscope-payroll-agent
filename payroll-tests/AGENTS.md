# payroll-tests

## 职责

跨模块集成测试、端到端测试、架构规则测试。**本仓库全部测试都集中在这里**（其余模块没有 `src/test`），所以「改 common 只跑相关单测」这件事在这里做不到——只能按类名定向跑。

## 分组（Surefire `@Tag`）

| 标签 | 含义 | 何时跑 |
| --- | --- | --- |
| 无标签 | 纯单测，H2 内存库 + 桩 | PR 默认跑 |
| `integration` | `*WebTest`：起 Spring 上下文 + H2 + 桩聊天 | PR 排除，main/夜间跑 |
| `e2e` | 真模型/真链路（`Dev*E2eTest`） | 本地或夜间 |
| `eval` | 检索/路由效果评估（`KbRoutingEvalTest`） | 改了路由或检索时 |

CI 的 PR 档：`mvn -B -ntp -DexcludedGroups=integration,e2e,eval test`。

## 目录约定

```
payroll-tests/src/test/java/io/agentscope/examples/payroll/
├── *WebTest.java            # 接口层集成测试（@Tag("integration")）
├── config/  db/  service/   # 按被测模块分子包
├── kb/  chat/  skill/  team/  profile/  router/
├── devteam/                 # 场景包
└── ArchitectureRulesTest    # ArchUnit 包边界规则（改包名/依赖方向必跑）
```

命名与被测类一致；`@DisplayName` 用中文写「行为」而不是「方法名」。

## 写测试的规则

1. Web 测试统一 `@SpringBootTest(webEnvironment=RANDOM_PORT)` + H2，认证默认关掉，需要时 `registry.add("payroll.auth.enabled", () -> "true")`。
2. **不要连真实 MySQL/Redis/ES**；只有 `DdlSyntaxTest` 例外，它靠 `PAYROLL_TEST_MYSQL_*` 环境变量，缺失时自动跳过——**跳过不等于通过**，改 DDL 时本地要手动配一次。
3. 每条 P0/P1 修复都要有一条「把修复删掉就会红」的用例，不能只测当前实现。
4. 断言金额用 `BigDecimal` 字符串比较，不要 `double`。
5. 新测试文件放在与被测模块对应的子包，不要全堆在根目录。

## 定向执行

```bash
mvn -o -pl payroll-tests -am test -Dtest=LlmUsageStoreTest \
    -Dsurefire.failIfNoSpecifiedTests=false
```

`-am` 会让无匹配测试的模块也进构建，**不加 `failIfNoSpecifiedTests=false` 会报 "No tests matching pattern"**。

## 坑

- 全量 `mvn -o test` 是唯一能发现 ArchUnit 违规的方式；改包结构后必须跑。
- 集成测试跑的是 H2（`MODE=MySQL`），**生产 MySQL 的方言差异测不出来**（索引长度、JSON 列、DDL 非事务）。
- 测试数量基线会变（历史上 668 ~ 985 都出现过），汇报时以当次实际输出为准，不要引用旧数字。
