package io.agentscope.examples.payroll.skill;

import io.agentscope.examples.payroll.domain.PayrollException;
import io.agentscope.examples.payroll.skill.JdbcSkillStore.Row;
import io.agentscope.examples.payroll.skill.SkillNacosSource.RemoteSkill;
import io.agentscope.examples.payroll.skill.SkillRuntimeReader.Runtime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 切换前对账（规格 §11.3）：只读，逐项比 Nacos 与库里每一技能，任一硬项非空即禁止切换。
 *
 * <p>比对口径全部取库侧写入时用的那一个函数（{@link SkillManifests#contentHash}），不在这里另立一套
 * 规范化规则——否则「对账通过」只证明本类自洽。正文比 {@code content_hash}，文件比 {@code path} 集合
 * 与逐文件 sha256，字节经 {@link SkillRuntimeReader#runtimeData} 真读一次，顺带证明 blob 可取回。
 *
 * <p>这是<b>切换前</b>的门禁：切库之后管理台只写 db，Nacos 会停在切换那一刻，本类此时报出的差异
 * 是双写窗口的正常结果，不能当故障看。
 */
public final class SkillMigrationCheck {

    private static final Logger log = LoggerFactory.getLogger(SkillMigrationCheck.class);

    /**
     * @param checkedFiles 本次是否真的逐文件比对了文件包：开关关闭（库里没有清单可读）和资源字节整批读不
     *     回来（blob 侧故障，原因写进 {@code problems}）两种情形都是 false，此时只比了正文与执行开关
     * @param extraInDb 只存在于库里的技能：切换前应当为空，切换后属正常增量，因此不进 {@code match}
     */
    public record Result(
            boolean match,
            int nacosCount,
            int dbCount,
            boolean checkedFiles,
            List<String> missingInDb,
            List<String> bodyMismatch,
            List<String> fileMissing,
            List<String> fileExtra,
            List<String> fileHashMismatch,
            List<String> executionMismatch,
            List<String> resourceHashMismatch,
            List<String> orphanEnvelope,
            List<String> extraInDb,
            List<String> problems) {

        /** §11.3 的输出形状：{@code {match, bodyMismatch[], fileMissing[], fileExtra[], orphanEnvelope[]}} 加扩展项。 */
        public Map<String, Object> asMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("match", match);
            out.put("nacosCount", nacosCount);
            out.put("dbCount", dbCount);
            out.put("checkedFiles", checkedFiles);
            out.put("missingInDb", missingInDb);
            out.put("bodyMismatch", bodyMismatch);
            out.put("fileMissing", fileMissing);
            out.put("fileExtra", fileExtra);
            out.put("fileHashMismatch", fileHashMismatch);
            out.put("executionMismatch", executionMismatch);
            out.put("resourceHashMismatch", resourceHashMismatch);
            out.put("orphanEnvelope", orphanEnvelope);
            out.put("extraInDb", extraInDb);
            out.put("problems", problems);
            return out;
        }

        /** 硬差异条数：{@code extraInDb} 不计，理由见 record 注释。 */
        public int problemCount() {
            return missingInDb.size()
                    + bodyMismatch.size()
                    + fileMissing.size()
                    + fileExtra.size()
                    + fileHashMismatch.size()
                    + executionMismatch.size()
                    + resourceHashMismatch.size()
                    + orphanEnvelope.size()
                    + problems.size();
        }
    }

    /** 逐类差异的收集桶；单独一个类是为了让 {@link #finish} 不必排 12 个位置参数。 */
    private static final class Buckets {
        /** 目录是否真读出来了：读失败时逐条比对没有基准，只能报「读不出来」。 */
        private boolean catalogRead = true;

        /**
         * 资源字节是否真读回来了：{@code runtimeData} 是整批一次取回，一个 blob 读不到就整批抛，
         * 此时逐技能比文件没有基准，只能报「blob 侧读不出来」。
         */
        private boolean runtimeRead = true;

        private final List<String> missingInDb = new ArrayList<>();
        private final List<String> bodyMismatch = new ArrayList<>();
        private final List<String> fileMissing = new ArrayList<>();
        private final List<String> fileExtra = new ArrayList<>();
        private final List<String> fileHashMismatch = new ArrayList<>();
        private final List<String> executionMismatch = new ArrayList<>();
        private final List<String> resourceHashMismatch = new ArrayList<>();
        private final List<String> orphanEnvelope = new ArrayList<>();
        private final List<String> extraInDb = new ArrayList<>();
        private final List<String> problems = new ArrayList<>();
    }

    private final SkillNacosSource source;
    private final SkillRuntimeReader reader;
    private final boolean filesEnabled;

    public SkillMigrationCheck(SkillNacosSource source, SkillRuntimeReader reader) {
        this(source, reader, SkillFileSettings.fromEnvironment().enabled());
    }

    /**
     * @param filesEnabled 与 {@link JdbcSkillStore} 用同一份 {@code PAYROLL_SKILL_FILES_ENABLED}：
     *     关闭时目录行上的 {@code resource_hash} 归零、{@code runtimeData} 不返回资源，
     *     再拿 Nacos 的文件清单去比就是自己造差异
     */
    public SkillMigrationCheck(
            SkillNacosSource source, SkillRuntimeReader reader, boolean filesEnabled) {
        if (source == null || reader == null) {
            throw new IllegalArgumentException("Nacos 来源与读端口必填");
        }
        this.source = source;
        this.reader = reader;
        this.filesEnabled = filesEnabled;
    }

    public Result check() {
        Buckets buckets = new Buckets();
        List<RemoteSkill> remotes = readRemotes(buckets);
        List<Row> rows = readCatalog(buckets);
        Map<String, Row> byId = new LinkedHashMap<>();
        rows.forEach(row -> byId.putIfAbsent(row.id(), row));
        Set<String> remoteIds = new LinkedHashSet<>();
        remotes.forEach(remote -> remoteIds.add(remote.id()));
        Map<String, Runtime> runtimes = readRuntimes(byId, buckets);
        try {
            buckets.orphanEnvelope.addAll(source.listOrphanEnvelopes(remoteIds));
        } catch (RuntimeException e) {
            // 「读不到残留」不等于「没有残留」，同样按硬差异处理。
            buckets.problems.add("列出 Nacos 文件包失败：" + message(e));
        }
        if (byId.isEmpty() && !buckets.catalogRead) {
            // 目录一行都没读出来就逐条比，等于把全表 Nacos 技能报成「库里缺失」：运维会去找根本
            // 不存在的导入漏洞，而真因（连不上库）只躺在 problems 里。空表本身是真差异，照旧往下比。
            return finish(remotes.size(), 0, buckets);
        }
        for (RemoteSkill remote : remotes) {
            Row row = byId.get(remote.id());
            if (row == null) {
                buckets.missingInDb.add(remote.id());
                continue;
            }
            compareBody(remote, row, buckets);
            if (remote.executionEnabled() != row.executionEnabled()) {
                buckets.executionMismatch.add(
                        remote.id()
                                + "：Nacos="
                                + remote.executionEnabled()
                                + " 库="
                                + row.executionEnabled());
            }
            if (filesEnabled && buckets.runtimeRead) {
                compareFiles(remote, row, runtimes.get(remote.id()), buckets);
            }
        }
        byId.keySet().stream()
                .filter(id -> !remoteIds.contains(id))
                .forEach(buckets.extraInDb::add);
        return finish(remotes.size(), byId.size(), buckets);
    }

    private List<RemoteSkill> readRemotes(Buckets buckets) {
        try {
            return source.readSkills();
        } catch (RuntimeException e) {
            // 读不到 Nacos 就不能宣布对账通过：门禁的失败方向是「不许切」，不是「当没有差异」。
            buckets.problems.add("读取 Nacos 技能失败：" + message(e));
            return List.of();
        }
    }

    private List<Row> readCatalog(Buckets buckets) {
        try {
            return reader.catalog();
        } catch (RuntimeException e) {
            buckets.catalogRead = false;
            buckets.problems.add("读取技能表失败：" + message(e));
            return List.of();
        }
    }

    private Map<String, Runtime> readRuntimes(Map<String, Row> byId, Buckets buckets) {
        if (!filesEnabled || byId.isEmpty()) {
            return Map.of();
        }
        try {
            return reader.runtimeData(byId.values());
        } catch (RuntimeException e) {
            // 字节取不回来就是 blob 侧问题（典型是 PAYROLL_SKILL_BLOB_ROOT 指错目录），正是本门禁要在
            // 切换前抓的事故。但整批只有一条：继续逐技能比会把这一次失败写成 N 条「版本行未落库」，
            // 运维会去查一张没问题的表。
            buckets.runtimeRead = false;
            buckets.problems.add(
                    "读取技能资源失败，本轮未比对文件包（正文与执行开关仍已比对）：" + message(e));
            return Map.of();
        }
    }

    private static void compareBody(RemoteSkill remote, Row row, Buckets buckets) {
        String expected;
        try {
            expected = SkillManifests.contentHash(SkillContentRules.requireBody(remote.body()));
        } catch (PayrollException e) {
            // 正文过不了写入口的量规：导入时就会被拒，这里只解释「库里为什么没有这一条」。
            buckets.problems.add(remote.id() + "：Nacos 正文不合格：" + e.getMessage());
            return;
        }
        if (!expected.equals(row.contentHash())) {
            buckets.bodyMismatch.add(
                    remote.id()
                            + "：Nacos="
                            + shortHash(expected)
                            + " 库="
                            + shortHash(row.contentHash()));
        }
    }

    private static void compareFiles(
            RemoteSkill remote, Row row, Runtime runtime, Buckets buckets) {
        if (runtime == null) {
            // 走到这里整批字节已经读回来了，缺这一条只可能是定义行还在、版本行被并发删掉
            // （见 {@link SkillRuntimeReader} 的契约）；blob 读不出来的情形已在 readRuntimes 短路，
            // 不会再级联成每人一条误诊。
            buckets.problems.add(remote.id() + "：库内运行期数据缺失（版本行未落库或被删）");
            return;
        }
        Map<String, String> actual = runtime.resources();
        Set<String> expectedPaths = new LinkedHashSet<>();
        Map<String, String> hashByPath = new LinkedHashMap<>();
        for (SkillFilePackage.SkillFile file : remote.files()) {
            expectedPaths.add(file.path());
            String content = actual.get(file.path());
            if (content == null) {
                buckets.fileMissing.add(remote.id() + "：" + file.path());
            } else if (!SkillManifests.contentHash(content)
                    .equals(SkillManifests.contentHash(file.content()))) {
                buckets.fileHashMismatch.add(remote.id() + "：" + file.path());
            }
        }
        actual.forEach(
                (path, content) -> {
                    if (!expectedPaths.contains(path)) {
                        buckets.fileExtra.add(remote.id() + "：" + path);
                    }
                    hashByPath.put(path, SkillManifests.contentHash(content));
                });
        String actualResourceHash = SkillManifests.resourceHashOf(hashByPath);
        if (!actualResourceHash.equals(row.resourceHash())) {
            buckets.resourceHashMismatch.add(
                    remote.id()
                            + "：实读="
                            + shortHash(actualResourceHash)
                            + " 行上="
                            + shortHash(row.resourceHash()));
        }
    }

    private Result finish(int nacosCount, int dbCount, Buckets buckets) {
        boolean match =
                buckets.missingInDb.isEmpty()
                        && buckets.bodyMismatch.isEmpty()
                        && buckets.fileMissing.isEmpty()
                        && buckets.fileExtra.isEmpty()
                        && buckets.fileHashMismatch.isEmpty()
                        && buckets.executionMismatch.isEmpty()
                        && buckets.resourceHashMismatch.isEmpty()
                        && buckets.orphanEnvelope.isEmpty()
                        && buckets.problems.isEmpty();
        Result result =
                new Result(
                        match,
                        nacosCount,
                        dbCount,
                        filesEnabled && buckets.runtimeRead,
                        List.copyOf(buckets.missingInDb),
                        List.copyOf(buckets.bodyMismatch),
                        List.copyOf(buckets.fileMissing),
                        List.copyOf(buckets.fileExtra),
                        List.copyOf(buckets.fileHashMismatch),
                        List.copyOf(buckets.executionMismatch),
                        List.copyOf(buckets.resourceHashMismatch),
                        List.copyOf(buckets.orphanEnvelope),
                        List.copyOf(buckets.extraInDb),
                        List.copyOf(buckets.problems));
        log.info(
                "[Skill] 切换前对账：Nacos {} 条 / 库 {} 条，{}（硬差异 {} 项）",
                nacosCount,
                dbCount,
                match ? "一致" : "不一致",
                result.problemCount());
        if (!match) {
            log.warn("[Skill] 对账明细：{}", result.asMap());
        }
        return result;
    }

    private static String shortHash(String hash) {
        return hash.length() <= 12 ? hash : hash.substring(0, 12);
    }

    private static String message(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
