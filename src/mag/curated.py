"""Load hand-curated state grants from data/curated/*.yaml."""
from __future__ import annotations

from datetime import date
from pathlib import Path

import yaml

from mag.federal import sort_key
from mag.model import Grant, parse_date, parse_money

REQUIRED = ("slug", "title", "agency", "url", "status", "types")
STATUSES = {"forecasted", "posted", "closed", "rolling"}
TYPES = {"fire", "ems"}


def _date(v) -> date | None:
    return v if isinstance(v, date) else parse_date(str(v) if v else None)


def _entry(state: str, e: dict) -> Grant:
    missing = [k for k in REQUIRED if not e.get(k)]
    if missing:
        raise ValueError(f"missing {', '.join(missing)}")
    if e["status"] not in STATUSES:
        raise ValueError(f"status must be one of {sorted(STATUSES)}")
    types = e["types"] if isinstance(e["types"], list) else [e["types"]]
    if not types or not set(types) <= TYPES:
        raise ValueError(f"types must be from {sorted(TYPES)}")
    return Grant(
        id=f"{state.lower()}-{e['slug']}",
        title=str(e["title"]), agency=str(e["agency"]),
        source=f"state:{state}", url=str(e["url"]), status=e["status"],
        open_date=_date(e.get("open_date")), close_date=_date(e.get("close_date")),
        award_floor=parse_money(e.get("award_floor")),
        award_ceiling=parse_money(e.get("award_ceiling")),
        types=tuple(sorted(set(types))), states=(state,), cfda=(),
        eligibility=str(e.get("eligibility") or "").strip(),
        description=str(e.get("description") or "").strip(),
    )


def load_curated(directory: Path) -> tuple[list[Grant], list[Grant], list[str], set[str]]:
    """Returns (published, review, errors, failed). failed holds the state codes of
    files that could not be read at all ("*" if the directory itself is missing),
    so the caller can keep last run's records for them."""
    published, review, errors, failed = [], [], [], set()
    directory = Path(directory)
    if not directory.is_dir():
        return [], [], [f"curated directory not found: {directory}"], {"*"}
    for path in sorted(directory.glob("*.yaml")):
        try:
            doc = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
            state = str(doc["state"]).upper()
            entries = doc.get("grants") or []
            if not isinstance(entries, list):
                raise TypeError("'grants' must be a list")
        except (yaml.YAMLError, KeyError, TypeError, ValueError, OSError, AttributeError) as ex:
            errors.append(f"{path.name}: unreadable file ({ex})")
            failed.add(path.stem.upper())
            continue
        for i, e in enumerate(entries):
            label = (e.get("slug") if isinstance(e, dict) else None) or f"entry #{i + 1}"
            try:
                if not isinstance(e, dict):
                    raise ValueError("entry is not a mapping")
                g = _entry(state, e)
            except (ValueError, TypeError) as ex:
                errors.append(f"{path.name}: {label}: {ex}")
                continue
            (published if e.get("verified") is True else review).append(g)
    return sorted(published, key=sort_key), sorted(review, key=sort_key), errors, failed
