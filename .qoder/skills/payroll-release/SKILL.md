---
name: payroll-release
description: 在本仓库执行提交与发布：核对改动范围、按任务范围暂存、写中文 conventional commit、通过 SSH 推送、以 main 为 base 建 PR。当用户要求提交代码、推送分支、创建或更新 PR、整理提交历史、核对 origin/main 与本地差异时使用。
---

# Payroll 提交与发布

## 前提

`commit`、`push`、`merge`、建 PR 都必须有用户当次明确授权。授权只覆盖被点名的动作与范围，不复用到下次。

## 1. 先看范围

```bash
git status
git diff
git diff --cached
git log --oneline -10
git log --oneline origin/main..HEAD
```

- `git status` 必须用普通模式，不要 `-uall`。
- 有未提交改动且即将执行可能丢改动的命令前，先确认或另存，不做 `reset --hard` / `clean -f`。
- 共享文件（如 `docs/`、`README.md`、看板相关文档）出现与本任务无关的修改时，按 hunk 暂存，不要整文件 `git add`。

## 2. 只暂存任务内文件

```bash
git add path/to/A.java path/to/B.vue
# 或按块
git add -p docs/xxx.md
```

禁止 `git add -A` / `git add .`。提交前 `git status` 复核暂存清单，出现 `.env`、密钥、`data/`、`logs/`、大二进制一律剔除。

## 3. 提交信息

中文，conventional 前缀，一句话说清「为什么」：

```
feat: 增加 token 用量看板与用量落库
fix: 修复运行期内部提交超时导致的会话失败
test: 补充用量聚合的时间窗与分面断言
```

- 主语省略，不用句号结尾，不加 emoji。
- 一次提交只做一件事；混合改动拆开。

## 4. 推送

```bash
git push origin <branch>
```

- remote 只能是 SSH：`git@github.com:wujieaiyan/agentscope-payroll-agent.git`。
- 禁止 `--force` / `--force-with-lease`，除非用户明确要求且已知影响。
- 禁止 `--no-verify`。hook 失败就修根因。
- 推送前再次确认 `git log origin/main..HEAD` 里没有他人提交或意外文件。

## 5. PR

```bash
gh pr create --base main --title "<标题>" --body "$(cat <<'EOF'
## 变更
- ...

## 验证
- mvn -o test：<数字> 通过
- 前端 npm run build / npm run test：通过
- 页面路径：<已验证的路径>
EOF
)"
```

- base 一律 `main`。
- 描述只写已验证的验证项；未验证的写清楚原因。
- 不自动合并、不自动关闭 issue，等用户指示。

## 红线

- 不复述任何口令、token、私钥；不把凭据写进 commit body、PR 描述或命令回显。
- 不删除本地 `data/`；不 `git clean`；不篡改 `.gitignore` 来「让状态变干净」。
