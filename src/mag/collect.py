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


def _previous(path: Path, keep) -> list[dict]:
    """Records from the last run's file whose source satisfies keep(source)."""
    try:
        return [g for g in json.loads(path.read_text()).get("grants", []) if keep(g.get("source", ""))]
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
    except Exception as e:  # any federal failure must not cost us curated output or old data
        msg = str(e) if isinstance(e, GrantsGovError) else f"unexpected {type(e).__name__}: {e}"
        errors.append(f"federal: {msg}")
        is_fed = lambda src: src == "federal"
        fed_pub_json, fed_rev_json = _previous(grants_path, is_fed), _previous(queue_path, is_fed)

    cur_pub, cur_rev, cur_errors, failed = load_curated(curated_dir)
    errors.extend(cur_errors)
    # A state file we couldn't read keeps last run's records for that state.
    was_failed = lambda src: src.startswith("state:") and ("*" in failed or src[6:] in failed)
    kept_pub, kept_rev = _previous(grants_path, was_failed), _previous(queue_path, was_failed)

    now = datetime.now(timezone.utc).isoformat(timespec="seconds")
    published = fed_pub_json + [g.to_json() for g in cur_pub] + kept_pub
    review = fed_rev_json + [g.to_json() for g in cur_rev] + kept_rev
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
