"""Collect federal fire/EMS grants from Grants.gov into published + review lists."""
from __future__ import annotations

import html
from datetime import date
from pathlib import Path

import yaml

from mag.grantsgov import Post, fetch_details, http_post, search
from mag.model import Grant, parse_date

DEFAULT_PROGRAMS = Path(__file__).with_name("programs.yaml")


def load_programs(path: Path | None = None) -> dict:
    return yaml.safe_load((path or DEFAULT_PROGRAMS).read_text())


def sort_key(g: Grant) -> tuple:
    return (g.close_date is None, g.close_date or date.max, g.id)


def _to_grant(hit: dict, types: tuple[str, ...], details: dict | None) -> Grant:
    d = details or {}
    return Grant(
        id=f"fed-{hit['id']}",
        title=html.unescape(hit.get("title") or ""),
        agency=html.unescape(hit.get("agency") or ""),
        source="federal",
        url=f"https://www.grants.gov/search-results-detail/{hit['id']}",
        status=hit.get("oppStatus") or "",
        open_date=parse_date(hit.get("openDate")),
        close_date=d.get("close_date") or parse_date(hit.get("closeDate")),
        award_floor=d.get("award_floor"),
        award_ceiling=d.get("award_ceiling"),
        types=types,
        states=(),
        cfda=tuple(hit.get("cfdaList") or ()),
        eligibility=d.get("eligibility", ""),
        description=d.get("description", ""),
    )


def collect_federal(programs: dict, post: Post = http_post) -> tuple[list[Grant], list[Grant]]:
    allow = programs.get("allowlist") or {}
    hits: dict[str, dict] = {}
    for kw in programs.get("keywords") or []:
        for h in search(kw, post=post):
            hits.setdefault(str(h["id"]), h)

    published, review = [], []
    for h in hits.values():
        matched = [allow[c] for c in h.get("cfdaList") or () if c in allow]
        if matched:
            types = tuple(sorted({t for m in matched for t in m.get("types", [])}))
            published.append(_to_grant(h, types, fetch_details(str(h["id"]), post=post)))
        else:
            review.append(_to_grant(h, (), None))
    return sorted(published, key=sort_key), sorted(review, key=sort_key)
