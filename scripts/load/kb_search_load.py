#!/usr/bin/env python3
"""KB retrieval load generator: login, multi-series search, P50/P95/P99 and error codes.

Covers capacity-resilience spec C6-2: concurrent retrieval against the admin test
endpoint (same kbSearchExecutor as chat/team links), reporting latency percentiles
and the HTTP/error-code distribution. Shares env credential conventions with
sse_load.py:

  PAYROLL_LOAD_ACCOUNTS=alice:secret,bob:secret
  PAYROLL_LOAD_USERNAME / PAYROLL_LOAD_PASSWORD   (single account fallback)

Series are auto-discovered from GET /api/kb/series unless --series-ids is given;
series with ready documents are preferred so retrieval actually hits chunks.

Examples:
  python scripts/load/kb_search_load.py --concurrency 5 --requests 100
  python scripts/load/kb_search_load.py --series-ids 3,7 --top-k 10 \
      --metrics-url http://127.0.0.1:8880/actuator/prometheus --out D:/tmp/kb-search.json
"""

from __future__ import annotations

import argparse
import asyncio
import itertools
import json
import os
import sys
import time

try:
    import httpx
except ImportError:
    sys.exit("httpx is required: pip install httpx")


DEFAULT_QUERIES = [
    "薪资核算的口径是什么",
    "差旅报销标准与审批层级",
    "绩效系数如何影响月度工资",
    "社保公积金缴纳基数",
    "加班费计算规则",
]


def percentile(values: list[float], pct: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, round(pct / 100.0 * (len(ordered) - 1))))
    return ordered[index]


def fmt_ms(value: float | None) -> str:
    return "-" if value is None else f"{value:.1f}"


def load_accounts() -> list[tuple[str, str]]:
    raw = os.environ.get("PAYROLL_LOAD_ACCOUNTS", "").strip()
    pairs: list[tuple[str, str]] = []
    if raw:
        for item in raw.split(","):
            item = item.strip()
            if not item:
                continue
            if ":" not in item:
                sys.exit("PAYROLL_LOAD_ACCOUNTS entries must be user:password")
            username, password = item.split(":", 1)
            pairs.append((username, password))
    else:
        username = os.environ.get("PAYROLL_LOAD_USERNAME", "").strip()
        password = os.environ.get("PAYROLL_LOAD_PASSWORD", "")
        if username and password:
            pairs.append((username, password))
    if not pairs:
        sys.exit(
            "set PAYROLL_LOAD_ACCOUNTS=user:pass,... or PAYROLL_LOAD_USERNAME/PAYROLL_LOAD_PASSWORD"
        )
    return pairs


async def login(client: httpx.AsyncClient, username: str, password: str) -> dict[str, str]:
    response = await client.post(
        "/api/auth/login", json={"username": username, "password": password}
    )
    if response.status_code >= 400:
        raise RuntimeError(
            f"login failed for {username}: HTTP {response.status_code} {response.text[:200]}"
        )
    body = response.json()
    if body.get("status") not in (None, "OK"):
        raise RuntimeError(f"login rejected for {username}: {body.get('message')}")
    return dict(client.cookies)


async def discover_series(client: httpx.AsyncClient) -> list[int]:
    response = await client.get("/api/kb/series", params={"page": 1, "size": 50})
    if response.status_code >= 400:
        raise RuntimeError(
            f"series list failed: HTTP {response.status_code} {response.text[:200]}"
        )
    page = response.json().get("data") or {}
    items = page.get("items") or []
    ready = [int(item["id"]) for item in items if int(item.get("readyDocCount") or 0) > 0]
    candidates = ready or [int(item["id"]) for item in items]
    if not candidates:
        sys.exit("no KB series visible to this account; create one or pass --series-ids")
    return candidates


async def search_once(
    client: httpx.AsyncClient,
    series_id: int,
    query: str,
    top_k: int,
    with_answer: bool,
    timeout_s: float,
) -> dict:
    started = time.perf_counter()
    sample: dict = {"series": series_id, "http": 0, "error": None, "hits": 0, "took_ms": None}
    try:
        response = await client.post(
            f"/api/kb/series/{series_id}/retrieval/test",
            json={"query": query, "topK": top_k, "withAnswer": with_answer},
            timeout=httpx.Timeout(connect=10.0, read=timeout_s, write=30.0, pool=timeout_s),
        )
        sample["http"] = response.status_code
        sample["latency_ms"] = (time.perf_counter() - started) * 1000.0
        if response.status_code != 200:
            sample["error"] = response.text[:200]
            return sample
        body = response.json()
        if body.get("status") not in (None, "OK"):
            sample["error"] = str(body.get("message") or body.get("status"))[:200]
            return sample
        data = body.get("data") or {}
        sample["hits"] = len(data.get("hits") or [])
        sample["took_ms"] = data.get("tookMs")
    except (httpx.HTTPError, OSError) as exc:
        sample["latency_ms"] = (time.perf_counter() - started) * 1000.0
        sample["error"] = f"{type(exc).__name__}: {exc}"
    return sample


async def worker(
    base_url: str,
    cookies: dict[str, str],
    queue: "asyncio.Queue[int]",
    series_ids: list[int],
    queries: list[str],
    top_k: int,
    with_answer: bool,
    timeout_s: float,
    results: list[dict],
) -> None:
    async with httpx.AsyncClient(
        base_url=base_url, cookies=cookies, follow_redirects=False, trust_env=False
    ) as client:
        while True:
            try:
                index = queue.get_nowait()
            except asyncio.QueueEmpty:
                return
            series_id = series_ids[index % len(series_ids)]
            query = queries[index % len(queries)]
            results.append(
                await search_once(client, series_id, query, top_k, with_answer, timeout_s)
            )


async def scrape_metrics(client: httpx.AsyncClient, url: str | None) -> dict[str, float]:
    if not url:
        return {}
    try:
        response = await client.get(url)
        if response.status_code != 200:
            return {"_error": float(response.status_code)}
    except (httpx.HTTPError, OSError):
        return {"_error": -1.0}
    metrics: dict[str, float] = {}
    for line in response.text.splitlines():
        if not line or line.startswith("#") or " " not in line:
            continue
        name, _, value = line.rpartition(" ")
        if name.startswith(("payroll_es_", "payroll_executor_", "payroll_kb_parse_")):
            try:
                metrics[name] = float(value)
            except ValueError:
                continue
    return metrics


def summarize(results: list[dict], wall_ms: float, series_count: int) -> dict:
    statuses: dict[str, int] = {}
    errors: dict[str, int] = {}
    latencies: list[float] = []
    by_series: dict[int, list[float]] = {}
    for sample in results:
        key = str(sample["http"]) if sample["http"] else "transport"
        statuses[key] = statuses.get(key, 0) + 1
        if sample["error"]:
            label = str(sample["error"]).split("：", 1)[0][:60]
            errors[label] = errors.get(label, 0) + 1
        if sample["latency_ms"] is not None:
            latencies.append(sample["latency_ms"])
            by_series.setdefault(sample["series"], []).append(sample["latency_ms"])
    return {
        "wall_ms": round(wall_ms, 1),
        "requests": len(results),
        "series": series_count,
        "status": statuses,
        "errors": errors,
        "latency_ms": {
            "p50": percentile(latencies, 50),
            "p95": percentile(latencies, 95),
            "p99": percentile(latencies, 99),
        },
        "by_series": {
            str(series): {
                "count": len(values),
                "p95": percentile(values, 95),
            }
            for series, values in sorted(by_series.items())
        },
        "empty_results": sum(1 for s in results if not s["error"] and s["hits"] == 0),
    }


def print_summary(summary: dict, before: dict, after: dict) -> None:
    print("\n=== KB search load summary ===")
    rate = summary["requests"] / max(summary["wall_ms"] / 1000.0, 0.001)
    print(
        f"requests={summary['requests']} series={summary['series']} "
        f"wall={summary['wall_ms'] / 1000.0:.1f}s rps={rate:.1f} status={summary['status']}"
    )
    latency = summary["latency_ms"]
    print(
        f"latency  p50={fmt_ms(latency['p50'])}ms p95={fmt_ms(latency['p95'])}ms "
        f"p99={fmt_ms(latency['p99'])}ms"
    )
    if summary["errors"]:
        print(f"errors={summary['errors']}")
    print(f"emptyResults={summary['empty_results']}")
    for series, stats in summary["by_series"].items():
        print(f"series {series}: count={stats['count']} p95={fmt_ms(stats['p95'])}ms")
    for key in sorted(set(before) | set(after)):
        if key.startswith("_error"):
            continue
        print(f"metric {key}: {before.get(key, 0.0):.0f} -> {after.get(key, 0.0):.0f}")


async def main_async(args: argparse.Namespace) -> None:
    accounts = load_accounts()
    limits = httpx.Limits(
        max_connections=args.concurrency + 8, max_keepalive_connections=args.concurrency + 8
    )
    cookies: list[dict[str, str]] = []
    async with httpx.AsyncClient(
        base_url=args.base_url, limits=limits, follow_redirects=False, trust_env=False
    ) as bootstrap:
        for username, password in accounts:
            cookies.append(await login(bootstrap, username, password))
        if args.series_ids:
            series_ids = [int(value) for value in args.series_ids.split(",") if value.strip()]
        else:
            series_ids = await discover_series(bootstrap)
        before = await scrape_metrics(bootstrap, args.metrics_url)

    queries = args.query or DEFAULT_QUERIES
    queue: "asyncio.Queue[int]" = asyncio.Queue()
    for index in range(args.requests):
        queue.put_nowait(index)
    results: list[dict] = []
    started = time.perf_counter()
    workers = [
        asyncio.create_task(
            worker(
                args.base_url,
                jar,
                queue,
                series_ids,
                queries,
                args.top_k,
                args.with_answer,
                args.timeout,
                results,
            )
        )
        for jar in itertools.islice(itertools.cycle(cookies), args.concurrency)
    ]
    await asyncio.gather(*workers)
    wall_ms = (time.perf_counter() - started) * 1000.0

    after: dict[str, float] = {}
    if args.metrics_url:
        async with httpx.AsyncClient(base_url=args.base_url, trust_env=False) as metrics_client:
            await asyncio.sleep(args.metrics_settle)
            after = await scrape_metrics(metrics_client, args.metrics_url)

    summary = summarize(results, wall_ms, len(series_ids))
    print_summary(summary, before, after)
    if args.out:
        payload = {
            "target": {"base_url": args.base_url, "series_ids": series_ids},
            "summary": summary,
            "metrics_before": before,
            "metrics_after": after,
        }
        with open(args.out, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, ensure_ascii=False, indent=2)
        print(f"written {args.out}")
    failures = sum(count for code, count in summary["status"].items() if code != "200")
    sys.exit(1 if args.fail_on_error and failures else 0)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base-url", default="http://127.0.0.1:8880")
    parser.add_argument("--concurrency", type=int, default=5)
    parser.add_argument("--requests", type=int, default=50)
    parser.add_argument("--series-ids", default=None)
    parser.add_argument("--query", action="append", default=None)
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--with-answer", action="store_true")
    parser.add_argument("--timeout", type=float, default=60.0)
    parser.add_argument("--metrics-url", default=None)
    parser.add_argument("--metrics-settle", type=float, default=2.0)
    parser.add_argument("--out", default=None)
    parser.add_argument("--fail-on-error", action="store_true")
    args = parser.parse_args()
    if args.concurrency < 1 or args.requests < 1:
        sys.exit("--concurrency and --requests must be >= 1")
    asyncio.run(main_async(args))


if __name__ == "__main__":
    main()
