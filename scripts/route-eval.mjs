#!/usr/bin/env node
/**
 * P3 路由回归：对评测集逐条调用 /api/agents/route-preview，按分类汇总并通过门槛。
 *
 * 用法：
 *   bash scripts/route-eval.sh [--web http://127.0.0.1:8080] [--limit 20]
 *   node scripts/route-eval.mjs --gate-only   # 不发请求，只校验评测集结构与门槛定义
 *
 * 门槛（规格 8.2）：payroll 100%、kb_direct ≥95%、kb_followup 100%、kb_synonym ≥85%、
 * kb_negative 误召回 ≤5% 且视频/文案 0 误召回、p95 < 300ms；P2.5 启用时
 * shadow diff 0、cache hit ≥70%。退出码非 0 表示未达标。
 */
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const args = new Map();
for (let i = 2; i < process.argv.length; i += 1) {
  const key = process.argv[i];
  if (!key.startsWith('--')) continue;
  const next = process.argv[i + 1];
  if (next && !next.startsWith('--')) {
    args.set(key.slice(2), next);
    i += 1;
  } else {
    args.set(key.slice(2), 'true');
  }
}

const WEB = (args.get('web') || process.env.WEB_BASE || 'http://127.0.0.1:8080').replace(/\/+$/, '');
const CASES_FILE = path.resolve(
  ROOT,
  args.get('cases') || 'payroll-tests/src/test/resources/routing/kb-routing-cases.json',
);
const BASELINE_FILE = path.resolve(
  ROOT,
  args.get('baseline') || 'output/p0-baseline/routing-baseline.json',
);
const LIMIT = Number(args.get('limit') || 0);
const P95_BUDGET_MS = Number(args.get('p95') || 300);
const GATE_ONLY = args.get('gate-only') === 'true';
const NO_BASELINE = args.get('no-baseline') === 'true';

const GATES = [
  { category: 'payroll', min: 1, label: 'payroll 金标 100%' },
  { category: 'kb_direct', min: 0.95, label: 'kb_direct ≥95%' },
  { category: 'kb_followup', min: 1, label: 'kb_followup 100%' },
  { category: 'kb_synonym', min: 0.85, label: 'kb_synonym ≥85%' },
];

const dataset = JSON.parse(await readFile(CASES_FILE, 'utf8'));
const cases = LIMIT > 0 ? dataset.cases.slice(0, LIMIT) : dataset.cases;
const byId = new Map(cases.map((item) => [item.id, item]));

if (GATE_ONLY) {
  console.log(`评测集 ${path.relative(ROOT, CASES_FILE)}，版本 ${dataset.version}，用例 ${cases.length}`);
  let passed = true;
  for (const gate of GATES) {
    const count = cases.filter((item) => item.category === gate.category).length;
    console.log(`  ${gate.category}: ${count} 条（${gate.label}）`);
    if (count === 0) {
      console.error(`未达标：缺少 ${gate.category} 用例`);
      passed = false;
    }
  }
  for (const category of ['kb_negative', 'mixed']) {
    const count = cases.filter((item) => item.category === category).length;
    console.log(`  ${category}: ${count} 条`);
    if (count === 0) {
      console.error(`未达标：缺少 ${category} 用例`);
      passed = false;
    }
  }
  process.exit(passed ? 0 : 1);
}

let baselineByQuestion = new Map();
if (!NO_BASELINE) {
  try {
    const baseline = JSON.parse(await readFile(BASELINE_FILE, 'utf8'));
    baselineByQuestion = new Map(
      (baseline.items || []).map((item) => [
        item.question,
        {
          target: item.preview?.data?.target ?? null,
          agentId: item.preview?.data?.agent_id ?? null,
        },
      ]),
    );
    console.log(`基线 ${path.relative(ROOT, BASELINE_FILE)}：${baselineByQuestion.size} 条`);
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
    console.warn(`基线缺失（${path.relative(ROOT, BASELINE_FILE)}），payroll 只校验 target 不校验 agent_id`);
  }
}

async function preview(item) {
  const body = {
    message: item.question,
    session_id: 0,
    kb_bound: item.fixture?.bound === true,
    kb_last_cited: item.fixture?.last_cited === true,
  };
  const response = await fetch(`${WEB}/api/agents/route-preview`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(15_000),
  });
  const text = await response.text();
  let payload;
  try {
    payload = text ? JSON.parse(text) : null;
  } catch {
    throw new Error(`HTTP ${response.status} 非 JSON：${text.slice(0, 200)}`);
  }
  if (response.status !== 200 || payload?.status !== 'OK') {
    throw new Error(`HTTP ${response.status} ${payload?.message || text.slice(0, 200)}`);
  }
  return payload.data;
}

function policyOf(data) {
  return data?.l0?.decision ?? 'NONE';
}

function needKbOf(data) {
  return data?.kb?.need === true;
}

function percentile(values, p) {
  if (!values.length) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.max(0, Math.ceil((p / 100) * sorted.length) - 1))];
}

const results = [];
for (const item of cases) {
  const record = { id: item.id, category: item.category, question: item.question, data: null, error: null };
  try {
    record.data = await preview(item);
  } catch (error) {
    record.error = error.message;
  }
  results.push(record);
  const policy = record.error ? 'ERROR' : policyOf(record.data);
  const need = record.error ? '-' : needKbOf(record.data);
  console.log(
    `[${record.id}] ${policy} need_kb=${need} ${record.data?.elapsed_ms ?? '-'}ms${record.error ? ` ERROR ${record.error}` : ''} :: ${item.question}`,
  );
}

const failures = [];
const stats = new Map();
for (const record of results) {
  const item = byId.get(record.id);
  const bucket = stats.get(record.category) || { total: 0, policy: 0, need: 0 };
  bucket.total += 1;
  if (record.error) {
    failures.push(`${record.id} 请求失败：${record.error}`);
    stats.set(record.category, bucket);
    continue;
  }
  if (policyOf(record.data) === item.expect.policy) bucket.policy += 1;
  else
    failures.push(
      `${record.id} policy 期望 ${item.expect.policy} 实际 ${policyOf(record.data)}`,
    );
  if (needKbOf(record.data) === item.expect.need_kb) bucket.need += 1;
  else
    failures.push(
      `${record.id} need_kb 期望 ${item.expect.need_kb} 实际 ${needKbOf(record.data)}`,
    );
  if (record.category === 'payroll' && item.expect.target) {
    const actualTarget = record.data?.target ?? null;
    if (actualTarget !== item.expect.target) {
      failures.push(`${record.id} target 期望 ${item.expect.target} 实际 ${actualTarget}`);
    }
    const baseline = baselineByQuestion.get(item.question);
    if (baseline?.agentId && record.data?.agent_id !== baseline.agentId) {
      failures.push(
        `${record.id} agent_id 期望 ${baseline.agentId} 实际 ${record.data?.agent_id}`,
      );
    }
  }
  stats.set(record.category, bucket);
}

const elapsed = results
  .map((record) => record.data?.elapsed_ms)
  .filter((value) => typeof value === 'number');
const p95 = percentile(elapsed, 95);

console.log('\n分类通过率：');
let passed = true;
for (const gate of GATES) {
  const bucket = stats.get(gate.category) || { total: 0, policy: 0, need: 0 };
  const rate = bucket.total ? Math.min(bucket.policy, bucket.need) / bucket.total : 0;
  console.log(
    `  ${gate.category}: ${bucket.policy}/${bucket.total} policy、${bucket.need}/${bucket.total} need_kb（${(rate * 100).toFixed(1)}%，门槛 ${(gate.min * 100).toFixed(0)}%）`,
  );
  if (rate < gate.min) {
    console.error(`  未达标：${gate.label}`);
    passed = false;
  }
}

const negative = results.filter((record) => record.category === 'kb_negative');
const falsePositive = negative.filter((record) => record.data && needKbOf(record.data));
const negativeRate = negative.length ? falsePositive.length / negative.length : 0;
console.log(
  `  kb_negative: ${falsePositive.length}/${negative.length} 误召回（${(negativeRate * 100).toFixed(1)}%，门槛 ≤5%）`,
);
if (negativeRate > 0.05) {
  console.error('  未达标：kb_negative 误召回 >5%');
  passed = false;
}
const mediaFalsePositive = falsePositive.filter((record) =>
  /视频|文案|画画|画一|写一段|写一篇/.test(record.question),
);
console.log(`  视频/文案误召回: ${mediaFalsePositive.length}（门槛 0）`);
if (mediaFalsePositive.length > 0) {
  console.error('  未达标：视频/文案出现误召回');
  passed = false;
}

console.log(`\np95=${p95}ms（门槛 <${P95_BUDGET_MS}ms，样本 ${elapsed.length}）`);
if (p95 >= P95_BUDGET_MS) {
  console.error(`未达标：p95 ${p95}ms ≥ ${P95_BUDGET_MS}ms`);
  passed = false;
}

const cacheRecords = results.filter((record) => record.data?.router_cache?.enabled === true);
if (cacheRecords.length) {
  const hits = cacheRecords.filter((record) => record.data.router_cache.hit === true).length;
  const hitRate = hits / cacheRecords.length;
  console.log(`router cache: ${hits}/${cacheRecords.length} 命中（${(hitRate * 100).toFixed(1)}%，门槛 ≥70%）`);
  if (hitRate < 0.7) {
    console.error('未达标：cache hit <70%');
    passed = false;
  }
} else {
  console.log('router cache: 未启用（payroll.router.semantic.payroll-mode=off/shadow），跳过命中门槛');
}

const shadowRecords = results.filter((record) => record.data?.router_shadow?.ran === true);
if (shadowRecords.length) {
  const diffs = shadowRecords.filter((record) => record.data.router_shadow.diff === true).length;
  console.log(`shadow 对比: ${shadowRecords.length} 次，diff ${diffs}（门槛 0）`);
  if (diffs > 0) {
    console.error('未达标：shadow diff >0');
    passed = false;
  }
} else {
  console.log('shadow 对比: 未采样，跳过');
}

console.log(
  '平均 kb_search 次数：由对话回归（turn 模式）统计，本脚本只覆盖路由预览，不触发检索。',
);

if (failures.length) {
  console.log('\n失败明细：');
  for (const failure of failures) console.log(`  ${failure}`);
}
console.log(passed ? '\nP3 路由门槛：通过' : '\nP3 路由门槛：未通过');
process.exit(passed ? 0 : 1);
