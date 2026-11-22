import os

import pytest

from mag.federal import collect_federal, load_programs


@pytest.mark.skipif(os.environ.get("MAG_LIVE") != "1", reason="set MAG_LIVE=1 to hit Grants.gov")
def test_live_search_returns_fire_grants():
    published, review = collect_federal(load_programs())
    assert isinstance(published, list) and isinstance(review, list)
