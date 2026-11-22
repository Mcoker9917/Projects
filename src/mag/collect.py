"""mag-collect: gather federal + curated grants into data/grants.json and review_queue.json."""
from __future__ import annotations

import argparse
import json
import os
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

from mag.curated import load_curated
from mag.federal import collect_federal, load_programs
from mag.grantsgov import GrantsGovError, Post, http_post


def _previous_federal(path: Path) -> list[dict]:
    try:
        return [g for g in json.loads(path.read_text()).get("grants", []) if g.get("source") == "federal"]
    except (OSError, ValueError, AttributeError):
        return []


def _write_atomic(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=path.parent, suffix=".tmp")
    with os.fdopen(fd, "w") as f:
        json.dump(payload, f, indent=2, ensure_ascii=False)
        f.write("\n")
    os.replace(tmp, path)


def run(out_dir: Path, curated_dir: Path, post: Post = http_post) -> int:
    out_dir = Path(out_dir)
    grants_path, queue_path = out_dir / "grants.json", out_dir / "review_queue.json"
    errors: list[str] = []

    try:
        fed_pub, fed_rev = collect_federal(load_programs(), post=post)
        fed_pub_json = [g.to_json() for g in fed_pub]
        fed_rev_json = [g.to_json() for g in fed_rev]
    except GrantsGovError as e:
        errors.append(f"federal: {e}")
        fed_pub_json, fed_rev_json = _previous_federal(grants_path), _previous_federal(queue_path)

    cur_pub, cur_rev, cur_errors = load_curated(curated_dir)
    errors.extend(cur_errors)

    now = datetime.now(timezone.utc).isoformat(timespec="seconds")
    published = fed_pub_json + [g.to_json() for g in cur_pub]
    review = fed_rev_json + [g.to_json() for g in cur_rev]
    _write_atomic(grants_path, {"generated": now, "grants": published})
    _write_atomic(queue_path, {"generated": now, "grants": review, "errors": errors})

    by_source: dict[str, int] = {}
    for g in published:
        key = g["source"].removeprefix("state:")
        by_source[key] = by_source.get(key, 0) + 1
    parts = ", ".join(f"{k} {v}" for k, v in sorted(by_source.items()))
    print(f"published {len(published)} ({parts}) | review {len(review)} | errors {len(errors)}")
    for e in errors:
        print(f"  ERROR {e}", file=sys.stderr)
    return 1 if errors else 0


def main() -> None:
    ap = argparse.ArgumentParser(description="Collect fire/EMS grants.")
    ap.add_argument("--out", default="data", type=Path)
    ap.add_argument("--curated", default="data/curated", type=Path)
    args = ap.parse_args()
    sys.exit(run(args.out, args.curated))
