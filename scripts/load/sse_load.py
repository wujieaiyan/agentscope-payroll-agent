#!/usr/bin/env python3
"""SSE load generator: login, create sessions, concurrent streams, TTFT and reconnect.

Covers capacity-resilience spec batch B-5: 200 concurrent SSE through nginx 8880,
first-event latency, contract gap detection and reconnects with after_seq.

Requires httpx and anyio>=4 (httpx 0.28 needs anyio 4; an older Anaconda anyio 2.x
fails at AsyncClient shutdown with "no attribute 'CancelScope'"). Use an isolated venv:

  py -3 -m venv D:/tmp/sse-load-venv
  D:/tmp/sse-load-venv/Scripts/python.exe -m pip install "anyio>=4" httpx
  D:/tmp/sse-load-venv/Scripts/python.exe scripts/load/sse_load.py --help

Credentials come from the environment only:
  PAYROLL_LOAD_ACCOUNTS=alice:secret,bob:secret   (preferred: one line per account)
  PAYROLL_LOAD_USERNAME / PAYROLL_LOAD_PASSWORD  (single account fallback)

Each user defaults to 2 concurrent turns. When --concurrency exceeds
accounts * --max-per-user the server answers 429 by design; use more accounts or
raise payroll.chat.max-concurrent-per-user on the target instance.

Examples:
  python scripts/load/sse_load.py --concurrency 20 --reconnect-ratio 0.3
  python scripts/load/sse_load.py --base-url http://127.0.0.1:8880 --concurrency 200
  python scripts/load/sse_load.py --metrics-url http://127.0.0.1:8880/actuator/prometheus
"""

from __future__ import annotations

import argparse
import asyncio
import itertools
import json
import os
import random
import sys
import time
from dataclasses import dataclass

try:
    import httpx
except ImportError:
    sys.exit("httpx is required: pip install httpx")


@dataclass
class Sample:
    status: int = 0
    ttft_ms: float | None = None
    first_text_ms: float | None = None
    done_ms: float | None = None
    events: int = 0
    gaps: int = 0
    terminal: str | None = None
    error: str | None = None
    reconnects: int = 0
    reconnect_ms: float | None = None


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


async def login(
    client: httpx.AsyncClient, username: str, password: str
) -> dict[str, str]:
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


async def create_session(client: httpx.AsyncClient, index: int) -> int:
    response = await client.post(
        "/api/chat/sessions", json={"title": f"sse-load-{index}-{int(time.time())}"}
    )
    if response.status_code >= 400:
        raise RuntimeError(
            f"create session failed: HTTP {response.status_code} {response.text[:200]}"
        )
    data = response.json().get("data") or {}
    session_id = data.get("id")
    if session_id is None:
        raise RuntimeError(f"session response missing id: {response.text[:200]}")
    return int(session_id)


async def stream_once(
    client: httpx.AsyncClient,
    session_id: int,
    message: str,
    sample: Sample,
    timeout_s: float,
) -> int:
    """Consume one stream; returns the last seen seq for the reconnect phase."""
    started = time.perf_counter()
    last_seq = 0
    event_name: str | None = None
    data_lines: list[str] = []
    try:
        async with client.stream(
            "POST",
            f"/api/chat/sessions/{session_id}/stream",
            json={"message": message},
            timeout=httpx.Timeout(connect=10.0, read=timeout_s, write=30.0, pool=30.0),
        ) as response:
            sample.status = response.status_code
            if response.status_code != 200:
                sample.error = (await response.aread())[:200].decode("utf-8", "replace")
                return last_seq
            async for line in response.aiter_lines():
                if line == "":
                    if not data_lines:
                        event_name = None
                        continue
                    raw = "\n".join(data_lines)
                    event_name, data_lines = event_name, []
                    sample.events += 1
                    elapsed = (time.perf_counter() - started) * 1000.0
                    if sample.ttft_ms is None:
                        sample.ttft_ms = elapsed
                    try:
                        payload = json.loads(raw)
                    except ValueError:
                        payload = {}
                    seq = payload.get("seq")
                    if isinstance(seq, int) and seq > 0:
                        if last_seq and seq > last_seq + 1:
                            sample.gaps += 1
                        last_seq = max(last_seq, seq)
                    if event_name == "text" and sample.first_text_ms is None:
                        sample.first_text_ms = elapsed
                    if event_name in ("done", "error"):
                        sample.terminal = event_name
                        sample.done_ms = elapsed
                        return last_seq
                    event_name = None
                    continue
                if line.startswith("event:"):
                    event_name = line[6:].strip()
                elif line.startswith("data:"):
                    data_lines.append(line[5:].lstrip())
                elif line.startswith("id:"):
                    candidate = line[3:].strip()
                    if candidate.isdigit():
                        last_seq = max(last_seq, int(candidate))
    except (httpx.HTTPError, OSError) as exc:
        sample.error = f"{type(exc).__name__}: {exc}"
    return last_seq


async def reconnect_once(
    client: httpx.AsyncClient,
    session_id: int,
    last_seq: int,
    sample: Sample,
    jitter_ms: int,
    timeout_s: float,
) -> None:
    if jitter_ms > 0:
        await asyncio.sleep(random.uniform(0.0, jitter_ms / 1000.0))
    try:
        active = await client.get(f"/api/chat/sessions/{session_id}/turns/active")
        turn = (active.json().get("data") or {}) if active.status_code == 200 else {}
        turn_id = turn.get("turn_id")
        if not turn_id:
            return
        started = time.perf_counter()
        headers = {"Last-Event-ID": str(last_seq)} if last_seq > 0 else {}
        async with client.stream(
            "GET",
            f"/api/chat/sessions/{session_id}/turns/{turn_id}/events",
            params={"after_seq": last_seq},
            headers=headers,
            timeout=httpx.Timeout(connect=10.0, read=timeout_s, write=30.0, pool=30.0),
        ) as response:
            if response.status_code != 200:
                sample.error = f"reconnect HTTP {response.status_code}"
                return
            async for line in response.aiter_lines():
                if line.startswith("data:") and line[5:].strip():
                    sample.reconnect_ms = (time.perf_counter() - started) * 1000.0
                    sample.reconnects += 1
                    return
    except (httpx.HTTPError, OSError) as exc:
        sample.error = f"reconnect {type(exc).__name__}: {exc}"


async def worker(
    base_url: str,
    cookies: dict[str, str],
    index: int,
    message: str,
    timeout_s: float,
    reconnect_ratio: float,
    jitter_ms: int,
) -> Sample:
    sample = Sample()
    async with httpx.AsyncClient(
        base_url=base_url, cookies=cookies, follow_redirects=False, trust_env=False
    ) as client:
        try:
            session_id = await create_session(client, index)
            last_seq = await stream_once(client, session_id, message, sample, timeout_s)
            if reconnect_ratio > 0 and random.random() < reconnect_ratio:
                await reconnect_once(
                    client, session_id, last_seq, sample, jitter_ms, timeout_s
                )
        except (httpx.HTTPError, OSError, RuntimeError) as exc:
            sample.error = f"{type(exc).__name__}: {exc}"
    return sample


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
        if name.startswith("payroll_sse_"):
            try:
                metrics[name] = float(value)
            except ValueError:
                continue
    return metrics


def summarize(samples: list[Sample], wall_ms: float, user_count: int) -> dict:
    statuses: dict[str, int] = {}
    for sample in samples:
        key = str(sample.status) if not sample.error or sample.status else "error"
        statuses[key] = statuses.get(key, 0) + 1
    ttfts = [s.ttft_ms for s in samples if s.ttft_ms is not None]
    texts = [s.first_text_ms for s in samples if s.first_text_ms is not None]
    dones = [s.done_ms for s in samples if s.done_ms is not None]
    reconnects = [s.reconnect_ms for s in samples if s.reconnect_ms is not None]
    return {
        "wall_ms": round(wall_ms, 1),
        "requests": len(samples),
        "accounts": user_count,
        "status": statuses,
        "ttft_ms": {
            "p50": percentile(ttfts, 50),
            "p95": percentile(ttfts, 95),
            "p99": percentile(ttfts, 99),
        },
        "first_text_ms": {
            "p50": percentile(texts, 50),
            "p95": percentile(texts, 95),
            "p99": percentile(texts, 99),
        },
        "done_ms": {
            "p50": percentile(dones, 50),
            "p95": percentile(dones, 95),
            "p99": percentile(dones, 99),
        },
        "terminal_events": sum(1 for s in samples if s.terminal),
        "gaps": sum(s.gaps for s in samples),
        "events": sum(s.events for s in samples),
        "errors": sum(1 for s in samples if s.error),
        "reconnects": len(reconnects),
        "reconnect_ms": {
            "p50": percentile(reconnects, 50),
            "p95": percentile(reconnects, 95),
        },
    }


def print_summary(summary: dict, before: dict, after: dict) -> None:
    print("\n=== SSE load summary ===")
    print(f"requests={summary['requests']} accounts={summary['accounts']} "
          f"wall={summary['wall_ms'] / 1000.0:.1f}s status={summary['status']}")
    ttft = summary["ttft_ms"]
    print(f"TTFT     p50={fmt_ms(ttft['p50'])}ms p95={fmt_ms(ttft['p95'])}ms "
          f"p99={fmt_ms(ttft['p99'])}ms")
    text = summary["first_text_ms"]
    print(f"firstText p50={fmt_ms(text['p50'])}ms p95={fmt_ms(text['p95'])}ms "
          f"p99={fmt_ms(text['p99'])}ms")
    done = summary["done_ms"]
    print(f"streamDone p50={fmt_ms(done['p50'])}ms p95={fmt_ms(done['p95'])}ms "
          f"p99={fmt_ms(done['p99'])}ms")
    print(f"events={summary['events']} terminal={summary['terminal_events']} "
          f"gaps={summary['gaps']} errors={summary['errors']}")
    reconnect = summary["reconnect_ms"]
    print(f"reconnects={summary['reconnects']} "
          f"firstReplay p50={fmt_ms(reconnect['p50'])}ms p95={fmt_ms(reconnect['p95'])}ms")
    if before or after:
        keys = sorted(set(before) | set(after))
        for key in keys:
            if key.startswith("_error"):
                continue
            print(f"metric {key}: {before.get(key, 0.0):.0f} -> {after.get(key, 0.0):.0f}")


async def main_async(args: argparse.Namespace) -> None:
    accounts = load_accounts()
    if args.concurrency > len(accounts) * args.max_per_user:
        print(
            f"warning: {args.concurrency} concurrent streams over {len(accounts)} accounts "
            f"x {args.max_per_user} permits will be rejected with 429 by design",
            file=sys.stderr,
        )
    limits = httpx.Limits(
        max_connections=args.concurrency + len(accounts) + 8,
        max_keepalive_connections=args.concurrency + len(accounts) + 8,
    )
    cookie_jars: list[dict[str, str]] = []
    async with httpx.AsyncClient(
        base_url=args.base_url, limits=limits, follow_redirects=False, trust_env=False
    ) as login_client:
        for username, password in accounts:
            cookie_jars.append(await login(login_client, username, password))
        before = await scrape_metrics(login_client, args.metrics_url)

    started = time.perf_counter()
    tasks = []
    for index, jar in enumerate(itertools.islice(itertools.cycle(cookie_jars), args.concurrency)):
        tasks.append(
            asyncio.create_task(
                worker(
                    args.base_url,
                    jar,
                    index,
                    args.message,
                    args.timeout,
                    args.reconnect_ratio,
                    args.jitter_ms,
                )
            )
        )
    samples = await asyncio.gather(*tasks)
    wall_ms = (time.perf_counter() - started) * 1000.0

    after: dict[str, float] = {}
    if args.metrics_url:
        async with httpx.AsyncClient(base_url=args.base_url, trust_env=False) as metrics_client:
            await asyncio.sleep(args.metrics_settle)
            after = await scrape_metrics(metrics_client, args.metrics_url)

    summary = summarize(samples, wall_ms, len(accounts))
    print_summary(summary, before, after)
    if args.out:
        payload = {"summary": summary, "metrics_before": before, "metrics_after": after}
        with open(args.out, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, ensure_ascii=False, indent=2)
        print(f"written {args.out}")
    failures = summary["errors"] + summary["status"].get("429", 0)
    sys.exit(1 if args.fail_on_error and failures else 0)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base-url", default="http://127.0.0.1:8880")
    parser.add_argument("--concurrency", type=int, default=20)
    parser.add_argument("--max-per-user", type=int, default=2)
    parser.add_argument(
        "--message",
        default="请用一句话说明本月薪资核算的口径。",
    )
    parser.add_argument("--timeout", type=float, default=120.0)
    parser.add_argument("--reconnect-ratio", type=float, default=0.0)
    parser.add_argument("--jitter-ms", type=int, default=3000)
    parser.add_argument("--metrics-url", default=None)
    parser.add_argument("--metrics-settle", type=float, default=2.0)
    parser.add_argument("--out", default=None)
    parser.add_argument("--fail-on-error", action="store_true")
    args = parser.parse_args()
    if args.concurrency < 1:
        sys.exit("--concurrency must be >= 1")
    asyncio.run(main_async(args))


if __name__ == "__main__":
    main()
