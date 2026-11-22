import json
from datetime import date
from pathlib import Path

import pytest

from mag.grantsgov import GrantsGovError, fetch_details, search

FIX = Path(__file__).parent / "fixtures"


def load(name):
    return json.loads((FIX / name).read_text())


def test_search_pages_until_hitcount():
    pages = [load("search_page1.json"), load("search_page2.json")]
    calls = []

    def post(url, body):
        calls.append(body)
        return pages[len(calls) - 1]

    hits = search("fire", post=post, page_size=2)
    assert [h["id"] for h in hits] == ["1", "2", "3"]
    assert calls[1]["startRecordNum"] == 2
    assert calls[0]["oppStatuses"] == "forecasted|posted"


def test_search_raises_on_errorcode():
    with pytest.raises(GrantsGovError):
        search("fire", post=lambda url, body: {"errorcode": 1, "msg": "x"})


def test_fetch_details_parses_fixture():
    d = fetch_details("363715", post=lambda url, body: load("fetch_363715.json"))
    assert d["award_ceiling"] == 5320000
    assert d["award_floor"] == 50000
    assert d["close_date"] == date(2027, 1, 31)
    assert "<" not in d["description"] and "Guidance Letter" in d["description"]


def test_fetch_details_missing_synopsis_fields():
    d = fetch_details("1", post=lambda url, body: {"errorcode": 0, "data": {"synopsis": {}}})
    assert d["award_ceiling"] is None and d["award_floor"] is None and d["close_date"] is None
    assert d["description"] == "" and d["eligibility"] == ""


def test_search_sends_multiword_keywords_as_exact_phrases():
    sent = []

    def post(url, body):
        sent.append(body["keyword"])
        return {"errorcode": 0, "data": {"hitCount": 0, "oppHits": []}}

    search("emergency medical services", post=post)
    search("firefighter", post=post)
    search('"already quoted"', post=post)
    assert sent == ['"emergency medical services"', "firefighter", '"already quoted"']
