"""Thin client for the public Grants.gov REST API (no key needed)."""
from __future__ import annotations

import html
import http.client
import json
import re
import urllib.error
import urllib.request
from typing import Callable

from mag.model import parse_date, parse_money

SEARCH_URL = "https://api.grants.gov/v1/api/search2"
FETCH_URL = "https://api.grants.gov/v1/api/fetchOpportunity"

Post = Callable[[str, dict], dict]


class GrantsGovError(Exception):
    pass


def http_post(url: str, body: dict, timeout: float = 30) -> dict:
    req = urllib.request.Request(
        url,
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json", "User-Agent": "mag-collect/0.1"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            if resp.status != 200:
                raise GrantsGovError(f"HTTP {resp.status} from {url}")
            return json.loads(resp.read())
    except (OSError, http.client.HTTPException, ValueError) as e:  # URLError, timeouts, resets, SSL, bad JSON
        raise GrantsGovError(f"{type(e).__name__}: {e}") from e


def _call(post: Post, url: str, body: dict) -> dict:
    resp = post(url, body)
    if not isinstance(resp, dict) or resp.get("errorcode") != 0:
        msg = resp.get("msg") if isinstance(resp, dict) else resp
        raise GrantsGovError(f"Grants.gov error: {msg}")
    return resp.get("data") or {}


def _phrase(keyword: str) -> str:
    """Grants.gov ORs bare words, so multi-word keywords must be exact phrases."""
    k = keyword.strip()
    return f'"{k}"' if " " in k and not k.startswith('"') else k


def search(keyword: str, post: Post = http_post, page_size: int = 100) -> list[dict]:
    hits: list[dict] = []
    keyword = _phrase(keyword)
    while True:
        data = _call(post, SEARCH_URL, {
            "keyword": keyword,
            "oppStatuses": "forecasted|posted",
            "rows": page_size,
            "startRecordNum": len(hits),
        })
        page = data.get("oppHits") or []
        hits.extend(page)
        if not page or len(hits) >= int(data.get("hitCount") or 0):
            return hits


def _strip_tags(text: str) -> str:
    text = re.sub(r"<[^>]+>", " ", text or "")
    return re.sub(r"\s+", " ", html.unescape(text)).strip()


def fetch_details(opp_id: str, post: Post = http_post) -> dict:
    syn = _call(post, FETCH_URL, {"opportunityId": int(opp_id)}).get("synopsis") or {}
    return {
        "description": _strip_tags(syn.get("synopsisDesc") or ""),
        "eligibility": _strip_tags(syn.get("applicantEligibilityDesc") or ""),
        "award_floor": parse_money(syn.get("awardFloor")),
        "award_ceiling": parse_money(syn.get("awardCeiling")),
        "close_date": parse_date(syn.get("responseDate")),
    }
