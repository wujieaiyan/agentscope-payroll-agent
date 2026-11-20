# 知识库入库任务启动恢复与重试上限

入库任务（`kb_ingest_task`）属于进程内异步任务：行先落库、再提交到 `kbIngestExecutor`。服务重启后残留的 `QUEUED / RUNNING / CANCELING` 任务以前只能靠人工在任务面板逐个重试；同一条任务链也没有重试次数上限。本文记录 2026-11-20 落地的启动恢复与上限规则。

## 启动恢复

Web 节点启动时由 `KbConfiguration.kbTaskRecoveryRunner` 调用 `KbTaskService.recoverInterrupted()`，按文档分组收敛：

| 任务状态 | 恢复动作 | 文档状态 |
| --- | --- | --- |
| `QUEUED` | 原任务重新提交执行器 | `PENDING` |
| `RUNNING` | 原任务置 `FAILED / SERVICE_RESTART`，清理未完成的分块与 ES 索引；未达上限时新建后续任务（`retry_of_task_id` 指向原任务，`retry_count + 1`）并提交 | `PENDING`；达到上限则 `FAILED / RECOVERY_EXHAUSTED` |
| `CANCELING` | 直接置 `CANCELED`，清理未完成的分块与 ES 索引 | `PENDING`（附「入库任务已取消」） |
| 同一文档多条活动任务 | 只保留任务号最大的一条，其余置 `FAILED / DUPLICATE_TASK` | 按保留任务继续处理 |
| 缺少 `document_id` 或文档已删除 | 置 `FAILED / DOCUMENT_MISSING` | 不变 |

恢复完成后按涉及的系列刷新文档/分块计数；有变更时写一条 `kb.task.recover` 审计并记录汇总日志。

清理分块与索引用于消除中断遗留的残留数据（分块计数会统计全部未删除文档的分块）。QUEUED 任务没有写入过产物，不触发清理。

## 重试上限

- 配置项 `payroll.kb.task-max-retries`（环境变量 `PAYROLL_KB_TASK_MAX_RETRIES`，默认 `3`），按任务链计次：初始任务 `retry_count = 0`，每次自动或手动重试加 1。
- 手动重试（`POST /api/kb/series/{seriesId}/tasks/{taskId}/retry`）在 `retry_count >= 上限` 时直接拒绝，提示排查原因后重新导入。
- 自动恢复同样受上限约束；达到上限后文档落 `FAILED / RECOVERY_EXHAUSTED`，不再新建任务。

## 开关

- `payroll.kb.recovery-enabled`（`PAYROLL_KB_RECOVERY_ENABLED`，默认 `true`）。关闭后遗留任务保持原状，由人工在任务面板处理。

## 代码位置

- 恢复与上限：`payroll-backend/src/main/java/io/agentscope/examples/payroll/kb/KbTaskService.java`
- 启动钩子：`payroll-backend/src/main/java/io/agentscope/examples/payroll/kb/KbConfiguration.java`
- 活动任务查询：`payroll-backend/src/main/java/io/agentscope/examples/payroll/kb/KbTaskStore.java`
- 产物清理复用：`payroll-backend/src/main/java/io/agentscope/examples/payroll/kb/KbIngestService.java`
- 测试：`payroll-tests/src/test/java/io/agentscope/examples/payroll/kb/KbTaskRecoveryTest.java`
