#!/usr/bin/env python3
"""KB parse load generator: synthetic N-page PDFs, concurrent upload, retry and 503 evidence.

Covers capacity-resilience spec C6-2: submit many 200-page PDFs at once and report
P50/P95/P99 for the full PENDING→READY pipeline together with the error-code
distribution. Documents are generated locally (text layer, no OCR) so parser
throughput dominates; each file is unique to bypass sha dedupe.

Credentials come from the environment only (same convention as sse_load.py):
  PAYROLL_LOAD_ACCOUNTS=alice:secret,bob:secret
  PAYROLL_LOAD_USERNAME / PAYROLL_LOAD_PASSWORD

Parser restart injection is opt-in: --restart-after 15 --restart-cmd "docker restart payroll-parser".
Only use it in remote parse mode (payroll.kb.parse.mode=remote); in inline mode the
"parser" is the Web process and restarting it aborts the load itself.

Examples:
  python scripts/load/kb_parse_load.py --series-id 3 --documents 5 --pages 200
  python scripts/load/kb_parse_load.py --documents 10 --concurrency 4 \
      --restart-after 20 --restart-cmd "docker restart payroll-agent-parser-1" \
      --metrics-url http://127.0.0.1:8880/actuator/prometheus --out D:/tmp/kb-parse.json
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import sys
import time

try:
    import httpx
except ImportError:
    sys.exit("httpx is required: pip install httpx")


TERMINAL = {"READY", "FAILED", "DISABLED", "DELETED"}


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


def build_pdf(pages: int, label: str) -> bytes:
    """Minimal text-layer PDF: one Helvetica line per page, no compression, no deps."""
    objects: list[bytes] = []

    def add(body: bytes) -> int:
        objects.append(body)
        return len(objects)

    catalog_id = add(b"")
    pages_id = add(b"")
    font_id = add(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
    page_ids: list[int] = []
    for page in range(1, pages + 1):
        text = f"{label} page {page}: monthly payroll policy fixture, social insurance base and approval rule."
        stream = (
            f"BT /F1 12 Tf 72 720 Td ({text}) Tj ET".encode("latin-1", "replace")
        )
        content_id = add(
            b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"\nendstream"
        )
        page_id = add(
            (
                f"<< /Type /Page /Parent {pages_id} 0 R /MediaBox [0 0 595 842] "
                f"/Resources << /Font << /F1 {font_id} 0 R >> >> /Contents {content_id} 0 R >>"
            ).encode("latin-1")
        )
        page_ids.append(page_id)
    objects[catalog_id - 1] = f"<< /Type /Catalog /Pages {pages_id} 0 R >>".encode()
    kids = " ".join(f"{page_id} 0 R" for page_id in page_ids)
    objects[pages_id - 1] = f"<< /Type /Pages /Kids [{kids}] /Count {pages} >>".encode()

    output = bytearray(b"%PDF-1.4\n%\xe2\xe3\xcf\xd3\n")
    offsets: list[int] = []
    for number, body in enumerate(objects, start=1):
        offsets.append(len(output))
        output += f"{number} 0 obj\n".encode() + body + b"\nendobj\n"
    xref_offset = len(output)
    output += f"xref\n0 {len(objects) + 1}\n".encode()
    output += b"0000000000 65535 f \n"
    for offset in offsets:
        output += f"{offset:010d} 00000 n \n".encode()
    output += (
        f"trailer\n<< /Size {len(objects) + 1} /Root {catalog_id} 0 R >>\n"
        f"startxref\n{xref_offset}\n%%EOF\n"
    ).encode()
    return bytes(output)


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


async def resolve_series(client: httpx.AsyncClient) -> int:
    response = await client.get("/api/kb/series", params={"page": 1, "size": 50})
    if response.status_code >= 400:
        raise RuntimeError(f"series list failed: HTTP {response.status_code}")
    items = (response.json().get("data") or {}).get("items") or []
    if not items:
        sys.exit("no KB series visible to this account; pass --series-id")
    return int(items[0]["id"])


async def submit_and_wait(
    base_url: str,
    cookies: dict[str, str],
    series_id: int,
    index: int,
    pdf: bytes,
    timeout_s: float,
    poll_s: float,
) -> dict:
    sample: dict = {
        "index": index,
        "http": 0,
        "document_id": None,
        "status": None,
        "error_code": None,
        "error": None,
        "history": [],
        "ready_ms": None,
        "page_count": None,
    }
    started = time.perf_counter()
    async with httpx.AsyncClient(
        base_url=base_url, cookies=cookies, follow_redirects=False, trust_env=False
    ) as client:
        filename = f"kb-parse-load-{index}-{int(time.time())}.pdf"
        try:
            response = await client.post(
                f"/api/kb/series/{series_id}/documents",
                files={"files": (filename, pdf, "application/pdf")},
                data={"dedupe_action": "OVERWRITE"},
                timeout=httpx.Timeout(connect=10.0, read=120.0, write=300.0, pool=60.0),
            )
        except (httpx.HTTPError, OSError) as exc:
            sample["http"] = 0
            sample["error"] = f"upload {type(exc).__name__}: {exc}"
            return sample
        sample["http"] = response.status_code
        if response.status_code != 200:
            sample["error"] = response.text[:200]
            return sample
        body = response.json()
        if body.get("status") not in (None, "OK"):
            sample["error"] = str(body.get("message") or body.get("status"))[:200]
            return sample
        data = body.get("data") or {}
        documents = data.get("documents") or []
        if data.get("failed"):
            sample["error"] = "; ".join(data.get("messages") or ["upload failed"])[:200]
            return sample
        if not documents:
            sample["error"] = "upload returned no documents"
            return sample
        document_id = int(documents[0]["id"])
        sample["document_id"] = document_id
        deadline = started + timeout_s
        while time.perf_counter() < deadline:
            poll = await client.get(
                f"/api/kb/series/{series_id}/documents/{document_id}",
                timeout=httpx.Timeout(connect=10.0, read=30.0, write=30.0, pool=30.0),
            )
            if poll.status_code != 200:
                sample["error"] = f"status poll HTTP {poll.status_code}"
                return sample
            view = poll.json().get("data") or {}
            status = view.get("status")
            if not sample["history"] or sample["history"][-1] != status:
                sample["history"].append(status)
            sample["status"] = status
            sample["page_count"] = view.get("pageCount")
            if status in TERMINAL:
                sample["ready_ms"] = (time.perf_counter() - started) * 1000.0
                sample["error_code"] = view.get("errorCode")
                if status != "READY":
                    sample["error"] = str(view.get("errorMessage") or status)[:200]
                return sample
            await asyncio.sleep(poll_s)
        sample["status"] = "TIMEOUT"
        sample["error"] = f"not terminal within {timeout_s:.0f}s"
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
        if name.startswith(("payroll_kb_parse_", "payroll_executor_")):
            try:
                metrics[name] = float(value)
            except ValueError:
                continue
    return metrics


async def restart_parser(delay_s: float, command: str) -> None:
    await asyncio.sleep(delay_s)
    print(f"restart injection after {delay_s:.0f}s: {command}", flush=True)
    process = await asyncio.create_subprocess_shell(
        command, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT
    )
    output, _ = await process.communicate()
    print(f"restart command exit={process.returncode} output={output[:200]!r}", flush=True)


def summarize(results: list[dict], wall_ms: float) -> dict:
    upload_status: dict[str, int] = {}
    terminal_status: dict[str, int] = {}
    error_codes: dict[str, int] = {}
    ready: list[float] = []
    retried = 0
    for sample in results:
        upload_status[str(sample["http"])] = upload_status.get(str(sample["http"]), 0) + 1
        status = sample["status"] or "NOT_SUBMITTED"
        terminal_status[status] = terminal_status.get(status, 0) + 1
        if sample["error_code"]:
            error_codes[sample["error_code"]] = error_codes.get(sample["error_code"], 0) + 1
        if sample["ready_ms"] is not None:
            ready.append(sample["ready_ms"])
        history = sample["history"]
        if "PENDING" in history[1:]:
            retried += 1
    return {
        "wall_ms": round(wall_ms, 1),
        "documents": len(results),
        "upload_status": upload_status,
        "terminal_status": terminal_status,
        "error_codes": error_codes,
        "retried": retried,
        "pipeline_ms": {
            "p50": percentile(ready, 50),
            "p95": percentile(ready, 95),
            "p99": percentile(ready, 99),
        },
        "errors": sum(1 for sample in results if sample["error"]),
    }


def print_summary(summary: dict, before: dict, after: dict) -> None:
    print("\n=== KB parse load summary ===")
    rate = summary["documents"] / max(summary["wall_ms"] / 1000.0, 0.001)
    print(
        f"documents={summary['documents']} wall={summary['wall_ms'] / 1000.0:.1f}s "
        f"throughput={rate * 60:.1f} docs/min upload={summary['upload_status']}"
    )
    print(f"terminal={summary['terminal_status']} retried={summary['retried']}")
    if summary["error_codes"]:
        print(f"errorCodes={summary['error_codes']}")
    pipeline = summary["pipeline_ms"]
    print(
        f"pipeline p50={fmt_ms(pipeline['p50'])}ms p95={fmt_ms(pipeline['p95'])}ms "
        f"p99={fmt_ms(pipeline['p99'])}ms errors={summary['errors']}"
    )
    for key in sorted(set(before) | set(after)):
        if key.startswith("_error"):
            continue
        print(f"metric {key}: {before.get(key, 0.0):.0f} -> {after.get(key, 0.0):.0f}")


async def main_async(args: argparse.Namespace) -> None:
    accounts = load_accounts()
    limits = httpx.Limits(
        max_connections=args.concurrency + 8, max_keepalive_connections=args.concurrency + 8
    )
    async with httpx.AsyncClient(
        base_url=args.base_url, limits=limits, follow_redirects=False, trust_env=False
    ) as bootstrap:
        cookies = [await login(bootstrap, username, password) for username, password in accounts]
        series_id = args.series_id or await resolve_series(bootstrap)
        before = await scrape_metrics(bootstrap, args.metrics_url)

    print(
        f"generating {args.documents} x {args.pages}-page PDFs for series {series_id}",
        flush=True,
    )
    stamp = int(time.time())
    pdfs = [build_pdf(args.pages, f"kb-parse-load-{index}-{stamp}") for index in range(args.documents)]

    restart = None
    if args.restart_cmd:
        if args.restart_after is None:
            sys.exit("--restart-cmd requires --restart-after")
        restart = asyncio.create_task(restart_parser(args.restart_after, args.restart_cmd))

    semaphore = asyncio.Semaphore(args.concurrency)
    results: list[dict] = []

    async def run(index: int) -> None:
        async with semaphore:
            results.append(
                await submit_and_wait(
                    args.base_url,
                    cookies[index % len(cookies)],
                    series_id,
                    index,
                    pdfs[index],
                    args.timeout,
                    args.poll_interval,
                )
            )

    started = time.perf_counter()
    await asyncio.gather(*(run(index) for index in range(args.documents)))
    wall_ms = (time.perf_counter() - started) * 1000.0
    if restart is not None:
        await restart
    results.sort(key=lambda sample: sample["index"])

    after: dict[str, float] = {}
    if args.metrics_url:
        async with httpx.AsyncClient(base_url=args.base_url, trust_env=False) as metrics_client:
            await asyncio.sleep(args.metrics_settle)
            after = await scrape_metrics(metrics_client, args.metrics_url)

    summary = summarize(results, wall_ms)
    print_summary(summary, before, after)
    if args.out:
        payload = {
            "target": {"base_url": args.base_url, "series_id": series_id, "pages": args.pages},
            "summary": summary,
            "documents": results,
            "metrics_before": before,
            "metrics_after": after,
        }
        with open(args.out, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, ensure_ascii=False, indent=2)
        print(f"written {args.out}")
    failures = summary["errors"] + summary["terminal_status"].get("FAILED", 0)
    failures += summary["terminal_status"].get("TIMEOUT", 0)
    failures += sum(count for code, count in summary["upload_status"].items() if code != "200")
    sys.exit(1 if args.fail_on_error and failures else 0)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--base-url", default="http://127.0.0.1:8880")
    parser.add_argument("--series-id", type=int, default=None)
    parser.add_argument("--documents", type=int, default=5)
    parser.add_argument("--pages", type=int, default=200)
    parser.add_argument("--concurrency", type=int, default=3)
    parser.add_argument("--timeout", type=float, default=900.0)
    parser.add_argument("--poll-interval", type=float, default=1.0)
    parser.add_argument("--restart-after", type=float, default=None)
    parser.add_argument("--restart-cmd", default=None)
    parser.add_argument("--metrics-url", default=None)
    parser.add_argument("--metrics-settle", type=float, default=2.0)
    parser.add_argument("--out", default=None)
    parser.add_argument("--fail-on-error", action="store_true")
    args = parser.parse_args()
    if args.documents < 1 or args.concurrency < 1 or args.pages < 1:
        sys.exit("--documents, --concurrency and --pages must be >= 1")
    asyncio.run(main_async(args))


if __name__ == "__main__":
    main()
