#!/usr/bin/env node
/**
 * 语义路由离线评估：对 3×30 条种子问句做留一法分类，输出混淆矩阵与延迟。
 *
 * 用法：
 *   node scripts/route-intents-eval.mjs [--model qwen3-embedding:0.6b] [--gate overall|confident]
 *   node scripts/route-intents-eval.mjs --es http://localhost:9200 --seed
 *
 * 门槛（规格 6.1，--gate overall，默认）：三个标签各自准确率 ≥ 90%，kb_read / other 互相误判 ≤ 5%。
 * --gate confident：只对运行时真正采纳的样本（top1 ≥ min-score 且 margin ≥ margin）计数，
 *   要求各标签准确率 ≥ 90%、互误 ≤ 5%、整体覆盖率 ≥ --min-coverage（默认 0.4）；
 *   低于置信线的样本运行时回退 L2，不计入门槛，但整体指标仍打印。
 * 退出码非 0 表示未达标，不得建立运行时索引。
 */
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const args = new Map();
for (let i = 2; i < process.argv.length; i += 1) {
  const key = process.argv[i];
  if (key.startsWith('--')) {
    const next = process.argv[i + 1];
    if (next && !next.startsWith('--')) {
      args.set(key.slice(2), next);
      i += 1;
    } else {
      args.set(key.slice(2), 'true');
    }
  }
}

const FILE =
  args.get('file') ||
  path.join(ROOT, 'payroll-common/src/main/resources/routing/route-intents.json');
const BASE = (args.get('base') || 'http://localhost:11434').replace(/\/+$/, '');
const MODEL = args.get('model') || 'nomic-embed-text';
const PREFIX = args.get('prefix') || 'none';
const ES = args.get('es') || '';
const SEED = args.get('seed') === 'true';
const MIN_SCORE = Number(args.get('min-score') || 0.50);
const MARGIN = Number(args.get('margin') || 0.05);
const CENTER = args.get('center') === 'true';
const GATE = args.get('gate') || 'overall';
const MIN_COVERAGE = Number(args.get('min-coverage') || 0.4);
if (GATE !== 'overall' && GATE !== 'confident') {
  throw new Error(`未知 --gate=${GATE}，可选 overall / confident`);
}

// 说明：Qwen3-Embedding 官方建议查询侧加任务指令、文档侧不加；LOOCV 因此分别计算两套向量。
const QWEN3_INSTRUCTION =
  'Instruct: 判断用户问题属于"制度问答"、"知识库管理"还是"无关任务"\nQuery: ';
const documentPrefix = PREFIX === 'nomic' ? 'search_document: ' : '';
const queryPrefix = PREFIX === 'nomic' ? 'search_query: ' : PREFIX === 'qwen3' ? QWEN3_INSTRUCTION : '';

async function embed(texts) {
  const response = await fetch(`${BASE}/api/embed`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ model: MODEL, input: texts }),
  });
  if (!response.ok) {
    throw new Error(`Ollama /api/embed HTTP ${response.status}: ${await response.text()}`);
  }
  const payload = await response.json();
  if (!Array.isArray(payload.embeddings) || payload.embeddings.length !== texts.length) {
    throw new Error('嵌入返回数量不匹配');
  }
  return payload.embeddings;
}

function cosine(a, b) {
  let dot = 0;
  let na = 0;
  let nb = 0;
  for (let i = 0; i < a.length; i += 1) {
    dot += a[i] * b[i];
    na += a[i] * a[i];
    nb += b[i] * b[i];
  }
  return dot / (Math.sqrt(na) * Math.sqrt(nb));
}

function percentile(values, p) {
  if (!values.length) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  return sorted[Math.min(sorted.length - 1, Math.ceil((p / 100) * sorted.length) - 1)];
}

const data = JSON.parse(await readFile(FILE, 'utf8'));
const samples = data.samples || [];
const labels = data.labels || [...new Set(samples.map((s) => s.label))];
const counts = new Map(labels.map((label) => [label, 0]));
for (const sample of samples) counts.set(sample.label, (counts.get(sample.label) || 0) + 1);
console.log(`文件 ${path.relative(ROOT, FILE)}，版本 ${data.version}，样本 ${samples.length}`);
for (const label of labels) console.log(`  ${label}: ${counts.get(label)} 条`);
const unbalanced = [...counts.values()].filter((n) => n !== 30);
if (unbalanced.length) {
  console.warn(`警告：各标签样本数不为 30：${unbalanced.join(',')}`);
}

console.log(`\n嵌入模型 ${MODEL}，前缀模式 ${PREFIX}，去均值 ${CENTER}，min-score=${MIN_SCORE}，margin=${MARGIN}`);
const docTexts = samples.map((s) => `${documentPrefix}${s.question}`);
const vectors = [];
for (let i = 0; i < docTexts.length; i += 16) {
  const batch = docTexts.slice(i, i + 16);
  const started = Date.now();
  const embedded = await embed(batch);
  vectors.push(...embedded);
  console.log(`  文档嵌入 ${i + batch.length}/${docTexts.length}（本批 ${Date.now() - started}ms）`);
}

// 查询侧与文档侧前缀不同（nomic / qwen3 指令）时，各自计算一套向量；留一法用查询向量对文档向量打分。
let queryVectors = vectors;
if (queryPrefix !== documentPrefix) {
  const queryTexts = samples.map((s) => `${queryPrefix}${s.question}`);
  queryVectors = [];
  for (let i = 0; i < queryTexts.length; i += 16) {
    const batch = queryTexts.slice(i, i + 16);
    queryVectors.push(...(await embed(batch)));
  }
  console.log(`  查询向量已按前缀重算：${queryVectors.length} 条`);
}

// BERT 系中文嵌入各向异性明显：所有向量共享一个主方向，余弦被压在 0.8~1.0。
// 减去种子均值后，同一查询/文档用同一中心，分类间距才可用；中心随种子版本一起版本化。
let center = null;
if (CENTER) {
  center = new Array(vectors[0].length).fill(0);
  for (const vector of vectors) {
    for (let d = 0; d < vector.length; d += 1) center[d] += vector[d];
  }
  for (let d = 0; d < center.length; d += 1) center[d] /= vectors.length;
  for (let i = 0; i < vectors.length; i += 1) {
    vectors[i] = vectors[i].map((value, d) => value - center[d]);
  }
  queryVectors = queryVectors.map((vector) => vector.map((value, d) => value - center[d]));
}

const matrix = new Map(labels.map((t) => [t, new Map(labels.map((p) => [p, 0]))]));
const confidentMatrix = new Map(labels.map((t) => [t, new Map(labels.map((p) => [p, 0]))]));
const confidentCounts = new Map(labels.map((label) => [label, 0]));
let correct = 0;
let confidentCorrect = 0;
let confidentTotal = 0;
const failures = [];
for (let i = 0; i < samples.length; i += 1) {
  const bestByLabel = new Map();
  for (let j = 0; j < samples.length; j += 1) {
    if (i === j) continue;
    const score = cosine(queryVectors[i], vectors[j]);
    const label = samples[j].label;
    if (!bestByLabel.has(label) || score > bestByLabel.get(label)) {
      bestByLabel.set(label, score);
    }
  }
  const ranked = [...bestByLabel.entries()].sort((a, b) => b[1] - a[1]);
  const predicted = ranked[0][0];
  const top1 = ranked[0][1];
  const margin = top1 - (ranked[1] ? ranked[1][1] : 0);
  const actual = samples[i].label;
  matrix.get(actual).set(predicted, matrix.get(actual).get(predicted) + 1);
  if (predicted === actual) correct += 1;
  const confident = top1 >= MIN_SCORE && margin >= MARGIN;
  if (confident) {
    confidentTotal += 1;
    confidentCounts.set(actual, confidentCounts.get(actual) + 1);
    confidentMatrix.get(actual).set(predicted, confidentMatrix.get(actual).get(predicted) + 1);
    if (predicted === actual) confidentCorrect += 1;
  }
  if (predicted !== actual) {
    failures.push({ actual, predicted, question: samples[i].question, top1, margin });
  }
}

function accuracy(cm) {
  const result = new Map();
  for (const actual of labels) {
    const row = cm.get(actual);
    const total = [...row.values()].reduce((a, b) => a + b, 0);
    result.set(actual, total ? row.get(actual) / total : 0);
  }
  return result;
}

function printMatrix(title, cm) {
  console.log(`\n${title}（行=标注，列=预测）`);
  console.log(['actual\\pred', ...labels].join('\t'));
  for (const actual of labels) {
    const row = cm.get(actual);
    console.log([actual, ...labels.map((p) => row.get(p))].join('\t'));
  }
  for (const [label, acc] of accuracy(cm)) {
    console.log(`  ${label} 准确率 ${(acc * 100).toFixed(1)}%`);
  }
}

printMatrix('混淆矩阵', matrix);
console.log(`总体准确率 ${(correct / samples.length * 100).toFixed(1)}%（${correct}/${samples.length}）`);
printMatrix(`高置信子集（覆盖 ${confidentTotal}/${samples.length}）`, confidentMatrix);
console.log(
  `高置信准确率 ${confidentTotal ? (confidentCorrect / confidentTotal * 100).toFixed(1) : '-'}%`,
);

const readToOther = matrix.get('kb_read').get('other') || 0;
const otherToRead = matrix.get('other').get('kb_read') || 0;
const cross = (readToOther + otherToRead) / (counts.get('kb_read') + counts.get('other'));
const acc = accuracy(matrix);
console.log(`\nkb_read→other ${readToOther} 条，other→kb_read ${otherToRead} 条，互相误判 ${(cross * 100).toFixed(1)}%`);

const latency = [];
for (let i = 0; i < Math.min(20, samples.length); i += 1) {
  const started = performance.now();
  const [vector] = await embed([`${queryPrefix}${samples[i].question}`]);
  if (center) {
    for (let d = 0; d < vector.length; d += 1) vector[d] -= center[d];
  }
  latency.push(performance.now() - started);
}
console.log(
  `查询嵌入延迟 p50=${percentile(latency, 50).toFixed(0)}ms，p95=${percentile(latency, 95).toFixed(0)}ms（${latency.length} 次）`,
);

if (failures.length) {
  console.log('\n误判明细：');
  for (const item of failures) {
    console.log(
      `  [${item.actual}→${item.predicted}] ${item.question}（top1=${item.top1.toFixed(3)}，margin=${item.margin.toFixed(3)}）`,
    );
  }
}

let passed = true;
if (GATE === 'overall') {
  for (const label of labels) {
    if ((acc.get(label) || 0) < 0.9) {
      console.error(
        `未达标：${label} 准确率 ${((acc.get(label) || 0) * 100).toFixed(1)}% < 90%`,
      );
      passed = false;
    }
  }
  if (cross > 0.05) {
    console.error(`未达标：kb_read/other 互相误判 ${(cross * 100).toFixed(1)}% > 5%`);
    passed = false;
  }
} else {
  const confidentAcc = accuracy(confidentMatrix);
  const confidentReadToOther = confidentMatrix.get('kb_read').get('other') || 0;
  const confidentOtherToRead = confidentMatrix.get('other').get('kb_read') || 0;
  const confidentCross =
    (confidentReadToOther + confidentOtherToRead) / (counts.get('kb_read') + counts.get('other'));
  const coverage = confidentTotal / samples.length;
  console.log('\n高置信口径拆解（仅统计运行时采纳的样本）：');
  for (const label of labels) {
    const labelCoverage = confidentCounts.get(label) / counts.get(label);
    console.log(
      `  ${label} 覆盖 ${confidentCounts.get(label)}/${counts.get(label)}（${(labelCoverage * 100).toFixed(1)}%），准确率 ${((confidentAcc.get(label) || 0) * 100).toFixed(1)}%`,
    );
  }
  console.log(
    `  kb_read↔other 高置信互误 ${confidentReadToOther + confidentOtherToRead} 条（占两类样本 ${(confidentCross * 100).toFixed(1)}%）`,
  );
  for (const label of labels) {
    // kb_admin 也纳入：高置信 admin 会取消 FORCE_KEYWORD 的检索，误判代价同样是漏检索。
    if ((confidentAcc.get(label) || 0) < 0.9) {
      console.error(
        `未达标：高置信 ${label} 准确率 ${((confidentAcc.get(label) || 0) * 100).toFixed(1)}% < 90%`,
      );
      passed = false;
    }
  }
  if (confidentCross > 0.05) {
    console.error(`未达标：高置信 kb_read/other 互误 ${(confidentCross * 100).toFixed(1)}% > 5%`);
    passed = false;
  }
  if (coverage < MIN_COVERAGE) {
    console.error(
      `未达标：高置信覆盖率 ${(coverage * 100).toFixed(1)}% < ${(MIN_COVERAGE * 100).toFixed(1)}%`,
    );
    passed = false;
  }
}
console.log(passed ? `\n离线门槛（${GATE}）：通过` : `\n离线门槛（${GATE}）：未通过`);

if (ES && SEED) {
  await seedElasticsearch(queryVectors);
}

async function seedElasticsearch(queryVectors) {
  const index = args.get('index') || 'payroll-route-intents-eval';
  const auth = args.get('es-user')
    ? `Basic ${Buffer.from(`${args.get('es-user')}:${args.get('es-pass') || ''}`).toString('base64')}`
    : '';
  const headers = { 'Content-Type': 'application/json' };
  if (auth) headers.Authorization = auth;
  const call = async (method, url, body) => {
    const response = await fetch(`${ES.replace(/\/+$/, '')}${url}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    const text = await response.text();
    if (!response.ok) throw new Error(`${method} ${url} HTTP ${response.status}: ${text}`);
    return text ? JSON.parse(text) : {};
  };
  const exists = await fetch(`${ES.replace(/\/+$/, '')}/${index}`, { method: 'HEAD', headers });
  if (!exists.ok) {
    await call('PUT', `/${index}`, {
      mappings: {
        properties: {
          vector: { type: 'dense_vector', dims: vectors[0].length },
          label: { type: 'keyword' },
          label_version: { type: 'keyword' },
          model: { type: 'keyword' },
          question: { type: 'text' },
        },
      },
    });
  }
  const options = samples
    .map((sample, i) => `${JSON.stringify({ index: { _index: index, _id: `${data.version}-${i}` } })}\n${JSON.stringify({
      vector: vectors[i],
      label: sample.label,
      label_version: data.version,
      model: MODEL,
      question: sample.question,
    })}`)
    .join('\n');
  const bulkResponse = await fetch(`${ES.replace(/\/+$/, '')}/_bulk?refresh=true`, {
    method: 'POST',
    headers: { ...headers, 'Content-Type': 'application/x-ndjson' },
    body: `${options}\n`,
  });
  if (!bulkResponse.ok) {
    throw new Error(`_bulk HTTP ${bulkResponse.status}: ${await bulkResponse.text()}`);
  }
  const bulk = await bulkResponse.json();
  if (bulk.errors) {
    console.error(' _bulk 存在错误，请检查映射');
  }
  let hit = 0;
  for (let i = 0; i < samples.length; i += 1) {
    const response = await call('POST', `/${index}/_search`, {
      size: 90,
      query: {
        script_score: {
          query: { bool: { filter: [{ term: { label_version: data.version } }, { term: { model: MODEL } }] } },
          script: {
            source: "cosineSimilarity(params.q, 'vector') + 1.0",
            params: { q: queryVectors[i] },
          },
        },
      },
    });
    const hits = response.hits?.hits || [];
    const bestByLabel = new Map();
    for (const item of hits) {
      if (item._source.question === samples[i].question) continue;
      const score = Number(item._score) - 1.0;
      const label = item._source.label;
      if (!bestByLabel.has(label) || score > bestByLabel.get(label)) bestByLabel.set(label, score);
    }
    const ranked = [...bestByLabel.entries()].sort((a, b) => b[1] - a[1]);
    if (ranked[0]?.[0] === samples[i].label) hit += 1;
  }
  console.log(`\nES 校验：索引 ${index}，命中 ${hit}/${samples.length}（${(hit / samples.length * 100).toFixed(1)}%）`);
}

process.exit(passed ? 0 : 1);
