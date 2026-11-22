"""The Grant record and small parsers shared by every source."""
from __future__ import annotations

import re
from dataclasses import asdict, dataclass
from datetime import date, datetime


@dataclass(frozen=True)
class Grant:
    id: str
    title: str
    agency: str
    source: str  # "federal" | "state:OK" | "private"
    url: str
    status: str  # "forecasted" | "posted" | "closed" | "rolling"
    open_date: date | None
    close_date: date | None
    award_floor: int | None
    award_ceiling: int | None
    types: tuple[str, ...]  # subset of ("ems", "fire"), sorted
    states: tuple[str, ...]  # empty = nationwide
    cfda: tuple[str, ...]
    eligibility: str
    description: str

    def to_json(self) -> dict:
        out = asdict(self)
        for key in ("open_date", "close_date"):
            out[key] = out[key].isoformat() if out[key] else None
        for key in ("types", "states", "cfda"):
            out[key] = list(out[key])
        return out


_LONG_DATE = re.compile(r"^([A-Z][a-z]{2}) (\d{1,2}), (\d{4})")


def parse_date(s: str | None) -> date | None:
    """Parse MM/DD/YYYY, 'Mon DD, YYYY ...' or YYYY-MM-DD; None if blank/unparseable."""
    if not s or not isinstance(s, str):
        return None
    s = s.strip()
    m = _LONG_DATE.match(s)
    if m:
        s, fmt = " ".join(m.groups()), "%b %d %Y"
    elif "/" in s:
        fmt = "%m/%d/%Y"
    else:
        fmt = "%Y-%m-%d"
    try:
        return datetime.strptime(s, fmt).date()
    except ValueError:
        return None


def parse_money(s: str | int | None) -> int | None:
    """Positive whole-dollar amount, or None for blank/'none'/0/non-numeric."""
    if s is None or isinstance(s, bool):
        return None
    try:
        value = int(float(str(s).replace(",", "").replace("$", "").strip()))
    except ValueError:
        return None
    return value if value > 0 else None
