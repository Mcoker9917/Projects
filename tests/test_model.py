from datetime import date
from mag.model import Grant, parse_date, parse_money

def test_parse_date_formats():
    assert parse_date("01/31/2027") == date(2027, 1, 31)
    assert parse_date("Jan 31, 2027 12:00:00 AM EST") == date(2027, 1, 31)
    assert parse_date("2027-01-31") == date(2027, 1, 31)
    assert parse_date("") is None and parse_date(None) is None and parse_date("soon") is None

def test_parse_money():
    assert parse_money("5320000") == 5320000
    assert parse_money(30000) == 30000
    for bad in ("", "none", None, "0", "N/A"):
        assert parse_money(bad) is None

def test_grant_to_json_roundtrips_types():
    g = Grant(id="fed-1", title="T", agency="A", source="federal", url="u", status="posted",
              open_date=date(2026, 1, 1), close_date=None, award_floor=None, award_ceiling=5,
              types=("ems", "fire"), states=(), cfda=("97.044",), eligibility="", description="")
    j = g.to_json()
    assert j["open_date"] == "2026-01-01" and j["close_date"] is None
    assert j["types"] == ["ems", "fire"] and j["states"] == []
