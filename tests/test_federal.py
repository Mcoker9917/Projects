from datetime import date

from mag.federal import collect_federal, load_programs
from mag.grantsgov import FETCH_URL, SEARCH_URL

PROGRAMS = {
    "allowlist": {"97.044": {"name": "AFG", "types": ["fire", "ems"]}},
    "keywords": ["fire", "ems"],
}


def hit(i, title="T", cfda=("97.044",), close="01/31/2027", status="posted"):
    return {"id": str(i), "number": f"N-{i}", "title": title, "agency": "FEMA",
            "openDate": "08/25/2026", "closeDate": close, "oppStatus": status,
            "cfdaList": list(cfda)}


def fake_post(by_keyword, details=None):
    fetched = []

    def post(url, body):
        if url == SEARCH_URL:
            hits = by_keyword.get(body["keyword"], [])
            return {"errorcode": 0, "data": {"hitCount": len(hits), "oppHits": hits}}
        assert url == FETCH_URL
        fetched.append(str(body["opportunityId"]))
        return {"errorcode": 0, "data": {"synopsis": (details or {}).get(str(body["opportunityId"]), {})}}

    post.fetched = fetched
    return post


def test_allowlisted_hit_is_published_with_types():
    post = fake_post({"fire": [hit(1)]})
    published, review = collect_federal(PROGRAMS, post=post)
    assert [g.id for g in published] == ["fed-1"] and review == []
    assert published[0].types == ("ems", "fire")
    assert published[0].url == "https://www.grants.gov/search-results-detail/1"
    assert published[0].source == "federal" and published[0].states == ()


def test_non_allowlisted_hit_goes_to_review():
    post = fake_post({"fire": [hit(2, cfda=["93.142"])]})
    published, review = collect_federal(PROGRAMS, post=post)
    assert published == [] and [g.id for g in review] == ["fed-2"]
    assert review[0].types == ()
    assert post.fetched == []


def test_duplicate_hits_across_keywords_deduped():
    post = fake_post({"fire": [hit(1)], "ems": [hit(1)]})
    published, review = collect_federal(PROGRAMS, post=post)
    assert len(published) == 1 and post.fetched == ["1"]


def test_title_entities_unescaped():
    post = fake_post({"fire": [hit(1, title="Victim&rsquo;s &ndash; Program")]})
    published, _ = collect_federal(PROGRAMS, post=post)
    assert published[0].title == "Victim’s – Program"


def test_forecasted_blank_close_date_ok():
    post = fake_post({"fire": [hit(1, close="", status="forecasted"), hit(2, close="03/01/2027")]})
    published, _ = collect_federal(PROGRAMS, post=post)
    assert [g.id for g in published] == ["fed-2", "fed-1"]
    assert published[1].close_date is None and published[0].close_date == date(2027, 3, 1)


def test_packaged_programs_file_loads():
    p = load_programs()
    assert "97.044" in p["allowlist"] and p["keywords"]
