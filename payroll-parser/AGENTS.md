# payroll-parser

## 职责

独立解析进程（默认 8300）：把 PDF / Office / 图片转成文本，供知识库入库与对话附件使用。**依赖 `payroll-common` 的 `kb/parse` 协议类型，但不依赖其服务层。**

为什么单独一个进程：解析会吃 CPU/内存、可能卡住（大 PDF、OCR），不能拖垮 Web 与 runtime。

## 目录地图

| 类 | 职责 |
| --- | --- |
| `ParserApplication` | 启动入口（8300） |
| `KbParseController` | `/internal/kb/parse`、`/internal/kb/chat/parse`，HMAC 校验 |
| `KbParserService` | 按格式分派（PDF/Word/Excel/图片/纯文本） |
| `ArtifactCleaner` | 解析产物目录清理 |
| `ocr/OcrExtractionService` | OCR 编排（引擎选择、质量门、重试） |
| `ocr/TesseractImageExtractor` / `RapidOcrExtractor` | 两种 OCR 引擎 |
| `ocr/OcrQualityGate` | 低质量结果判定（丢字/乱码/空白比） |
| `ocr/OcrEngineProperties` | 引擎参数（dpi、语言、并发） |

## 契约

- 请求/响应类型在 `payroll-common/.../kb/parse/*`（两端共用），**不要在 parser 里再定义一套 DTO**。
- 支持取消：`KbAbortSignal` / `KbParseCanceled`；可重试：`KbParseRetryableException`；限额：`KbParseLimitException`。
- 调用方是 Web 的 `KbParseClient`，走 HMAC；未配置 `PAYROLL_PARSE_TOKEN` 时 fail-closed。
- 模式由 `payroll.kb.parse.mode=inline|remote` 决定，`remote` 才会走本进程。

## 改这里的规则

1. 新增格式：加一个分派分支 + 一条「损坏文件不抛 500」的测试。
2. OCR 参数（dpi/语言/超时）只从 `OcrEngineProperties` 读，不要硬编码。
3. 解析产物必须落在可配置目录，且由 `ArtifactCleaner` 负责回收；不要写系统临时目录。
4. 异常码要能被 Web 侧映射成可读信息（`KbParseError`），不要把堆栈抛给调用方。

## 坑

- 依赖本机 OCR 引擎与字体；镜像里缺语言包时表现为「解析成功但全是乱码」，不是异常。
- 大文件并发会打满 CPU：并发上限来自配置，改之前先看 `docs/capacity-resilience-remediation-spec.md` C0。
- 解析与入库是两段：解析成功不代表入库成功（切块/嵌入在 Web 侧）。
- 端口 8300 只在 `remote` 模式下被调用；compose 的健康检查打在 `/actuator/health/liveness`。

## 验证

```bash
mvn -o -pl payroll-parser -am -DskipTests package
curl -s http://127.0.0.1:8300/actuator/health
```

端到端：Web 侧上传一个 PDF → `kb_ingest_task` 有记录 → 文档详情能读到切块。
