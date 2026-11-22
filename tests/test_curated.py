from pathlib import Path

from mag.curated import load_curated

SEED_DIR = Path(__file__).parents[1] / "data" / "curated"

GOOD = """
  - slug: good
    title: Good Grant
    agency: Agency
    url: https://example.org
    status: posted
    types: [fire]
    close_date: 2027-09-01
    award_ceiling: 30000
    verified: {verified}
"""


def write(tmp_path, body):
    (tmp_path / "ok.yaml").write_text("state: OK\ngrants:\n" + body)
    return tmp_path


def test_verified_entry_published_unverified_reviewed(tmp_path):
    body = GOOD.format(verified="true") + GOOD.replace("slug: good", "slug: other").format(verified="false")
    published, review, errors = load_curated(write(tmp_path, body))
    assert [g.id for g in published] == ["ok-good"] and [g.id for g in review] == ["ok-other"]
    assert errors == []
    g = published[0]
    assert g.source == "state:OK" and g.states == ("OK",) and g.award_ceiling == 30000
    assert str(g.close_date) == "2027-09-01"


def test_missing_required_field_reports_file_and_slug_and_continues(tmp_path):
    bad = GOOD.replace("    agency: Agency\n", "").replace("slug: good", "slug: broken").format(verified="true")
    published, review, errors = load_curated(write(tmp_path, bad + GOOD.format(verified="true")))
    assert [g.id for g in published] == ["ok-good"]
    assert len(errors) == 1 and "ok.yaml" in errors[0] and "broken" in errors[0]


def test_invalid_type_rejected(tmp_path):
    body = GOOD.replace("types: [fire]", "types: [police]").format(verified="true")
    published, review, errors = load_curated(write(tmp_path, body))
    assert published == [] and len(errors) == 1


def test_seed_file_loads_into_review():
    published, review, errors = load_curated(SEED_DIR)
    assert (len(published), len(review), errors) == (0, 1, [])
