import json
from pathlib import Path

from mag.collect import run
from mag.grantsgov import SEARCH_URL, FETCH_URL, GrantsGovError

SEED_DIR = Path(__file__).parents[1] / "data" / "curated"


def ok_post(url, body):
    if url == SEARCH_URL:
        hits = [{"id": "9", "title": "AFG", "agency": "FEMA", "openDate": "", "closeDate": "",
                 "oppStatus": "forecasted", "cfdaList": ["97.044"]}] if body["keyword"] == "firefighter" else []
        return {"errorcode": 0, "data": {"hitCount": len(hits), "oppHits": hits}}
    return {"errorcode": 0, "data": {"synopsis": {}}}


def failing_post(url, body):
    raise GrantsGovError("timeout")


def read(p):
    return json.loads(p.read_text())


def test_run_writes_both_files(tmp_path):
    assert run(tmp_path, SEED_DIR, post=ok_post) == 0
    grants = read(tmp_path / "grants.json")
    queue = read(tmp_path / "review_queue.json")
    assert [g["id"] for g in grants["grants"]] == ["fed-9"] and "generated" in grants
    assert [g["id"] for g in queue["grants"]] == ["ok-rural-fire-defense-80-20"]
    assert queue["errors"] == []


def test_federal_failure_keeps_previous_federal_records(tmp_path):
    prev = {"generated": "x", "grants": [{"id": "fed-1", "source": "federal"},
                                         {"id": "ok-x", "source": "state:OK"}]}
    (tmp_path / "grants.json").write_text(json.dumps(prev))
    (tmp_path / "review_queue.json").write_text(json.dumps(
        {"generated": "x", "grants": [{"id": "fed-2", "source": "federal"}], "errors": []}))
    assert run(tmp_path, SEED_DIR, post=failing_post) == 1
    ids = [g["id"] for g in read(tmp_path / "grants.json")["grants"]]
    assert "fed-1" in ids and "ok-x" not in ids
    queue = read(tmp_path / "review_queue.json")
    assert "fed-2" in [g["id"] for g in queue["grants"]]
    assert "federal: timeout" in queue["errors"]


def test_curated_error_sets_exit_code(tmp_path):
    cur = tmp_path / "curated"
    cur.mkdir()
    (cur / "ok.yaml").write_text("state: OK\ngrants:\n  - slug: bad\n")
    out = tmp_path / "out"
    assert run(out, cur, post=ok_post) == 1
    assert any("bad" in e for e in read(out / "review_queue.json")["errors"])
