# Grant Collection (Milestone 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A daily-runnable command that collects federal fire/EMS grants from
Grants.gov and hand-curated Oklahoma grants into one clean list. Anything
uncertain goes into a review queue instead of the published list.

**Architecture:** A small Python package, `mag` (Mutual Aid Grants). The
federal client talks to the public Grants.gov REST API. Opportunities whose
program number (CFDA / Assistance Listing) is on an allowlist are published.
Hits that came only from keyword searches go to review. State grants come from
YAML files the owner can read and edit. Only entries marked `verified: true`
are published. One command writes `data/grants.json` and
`data/review_queue.json`, and prints a summary.

**Tech Stack:** Python 3.13, the standard library (`urllib`, `json`, `html`,
`dataclasses`), PyYAML, pytest.

**Spec:** `docs/superpowers/specs/2026-11-22-fire-ems-grant-service-design.md`
(section "How it works → 1. Grant collection"). Later milestones are the
website, alerts, criteria research, the scorer, and paid drafting. Each gets
its own plan.

## Global Constraints

- **No paid services:** Grants.gov needs no API key.
- **Publishing rule:** "Low-confidence records wait in a short review queue for
  the owner and are not published until checked." Only allowlisted federal
  programs and `verified: true` curated entries reach `grants.json`.
- **First state:** Oklahoma, per the spec.
- **A failed source never wipes good data.** If a source fails, the previous
  output for that source is kept and the failure is reported.
- **Tests never hit the network.** The HTTP layer is injected. A single
  optional live smoke test runs only when `MAG_LIVE=1`.

## Verified API facts (checked 2026-11-22)

- **Search:** `POST https://api.grants.gov/v1/api/search2` with a JSON body
  `{"keyword": str, "oppStatuses": "forecasted|posted", "rows": int, "startRecordNum": int}`.
  - The response is `{"errorcode": 0, "data": {"hitCount": int, "oppHits": [...]}}`.
  - Each hit has `id`, `number`, `title`, `agency`, `openDate`, `closeDate`
    (`MM/DD/YYYY` or `""`), `oppStatus`, and `cfdaList`.
  - Titles contain HTML entities, for example `&ndash;` and `&rsquo;`.
  - The `cfda` request parameter returned 0 hits for `97.044`. So the plan
    searches by keyword and filters on `cfdaList` client-side.
- **Details:** `POST https://api.grants.gov/v1/api/fetchOpportunity` with
  `{"opportunityId": int}`.
  - The response's `data.synopsis` has `synopsisDesc`, `awardCeiling`,
    `awardFloor`, `responseDate` (`"Jan 31, 2027 12:00:00 AM EST"`), and
    `applicantEligibilityDesc`.
  - `awardCeiling` and `awardFloor` are numeric strings, possibly `""` or
    `"none"`.

## Review Focus

1. **Grants.gov down, a timeout, or a nonzero `errorcode`.** The run reports the
   failure, keeps the previous federal records, and exits nonzero. Tested in
   Task 5.
2. **A blank or odd close date.** Forecasted grants have `""`, and some grants
   are rolling. These become `close_date=None` with no crash. Tested in Tasks 1
   and 2.
3. **The same opportunity returned by several keyword queries.** It is deduped
   by id. Tested in Task 3.
4. **HTML entities in titles.** Titles are unescaped before output. Tested in
   Task 3.
5. **A curated YAML entry missing a required field.** The error names the file
   and entry, and the other entries still load. Tested in Task 4.

---

### Task 1: Project skeleton, Grant model, parsers

**Files:**
- Create: `pyproject.toml`, `src/mag/__init__.py`, `src/mag/model.py`
- Create: `.gitignore`
- Test: `tests/test_model.py`

`pyproject.toml` contents:
- Package `mag`, using the `src/` layout.
- Dependency: `pyyaml`.
- Optional dev dependency: `pytest`.
- Console script: `mag-collect = "mag.collect:main"`.
- `[tool.pytest.ini_options] pythonpath = ["src"]`.

**Interfaces:**
- Produces:
  - `Grant` is a frozen dataclass with these fields:
    - `id: str`. `"fed-<grants.gov id>"`, or `"<state lowercase>-<slug>"` for
      curated entries.
    - `title: str`, `agency: str`
    - `source: str`. One of `"federal"`, `"state:OK"`, `"private"`.
    - `url: str`
    - `status: str`. One of `"forecasted"`, `"posted"`, `"closed"`,
      `"rolling"`.
    - `open_date: date | None`, `close_date: date | None`
    - `award_floor: int | None`, `award_ceiling: int | None`
    - `types: tuple[str, ...]`. A subset of `("fire", "ems")`, sorted.
    - `states: tuple[str, ...]`. Empty means nationwide.
    - `cfda: tuple[str, ...]`
    - `eligibility: str`, `description: str`
  - `Grant.to_json() -> dict`: dates as ISO strings or `None`, tuples as lists.
  - `parse_date(s: str | None) -> date | None`. Accepts `"MM/DD/YYYY"`,
    `"Mon DD, YYYY ..."` (Grants.gov `responseDate`; ignore everything after
    the year), and ISO `"YYYY-MM-DD"`. Returns `None` for blank or unparseable
    input.
  - `parse_money(s: str | int | None) -> int | None`. `"5320000"` gives
    `5320000`. `""`, `"none"`, `None`, `"0"`, and non-numeric input give
    `None`.

- [ ] **Step 1: Write the failing tests**

```python
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
```

- [ ] **Step 2: Run the tests and confirm they fail.** Run
  `pip install -e '.[dev]' && pytest tests/test_model.py -v`. Expected: FAIL
  (ImportError).
- [ ] **Step 3: Implement `src/mag/model.py` to the interfaces above.** Add
  `.gitignore` with `__pycache__/`, `*.egg-info/`, `.pytest_cache/`.
- [ ] **Step 4: Run the tests and confirm they pass.** Run
  `pytest tests/test_model.py -v`. Expected: 3 passed.
- [ ] **Step 5: Commit.** Message: `feat: grant model and parsers`.

---

### Task 2: Grants.gov client

**Files:**
- Create: `src/mag/grantsgov.py`
- Create: `tests/fixtures/search_page1.json`, `tests/fixtures/search_page2.json`,
  `tests/fixtures/fetch_363715.json`. These are trimmed real responses,
  following the shapes in "Verified API facts".
- Test: `tests/test_grantsgov.py`

**Interfaces:**
- Consumes: `parse_date`, `parse_money` (Task 1).
- Produces:
  - `Post = Callable[[str, dict], dict]`. Takes a URL and a JSON body, and
    returns the decoded JSON. Tests pass fakes.
  - `http_post(url: str, body: dict, timeout: float = 30) -> dict`. Uses
    `urllib`. Raises `GrantsGovError` on network error or non-200.
  - `class GrantsGovError(Exception)`
  - `search(keyword: str, post: Post = http_post, page_size: int = 100) -> list[dict]`.
    - Pages with `startRecordNum` until it has collected `hitCount` hits.
    - Always sends `oppStatuses="forecasted|posted"`.
    - Raises `GrantsGovError` if `errorcode != 0`.
  - `fetch_details(opp_id: str, post: Post = http_post) -> dict`. Returns
    `{"description", "eligibility", "award_floor", "award_ceiling", "close_date"}`.
    - The money fields go through `parse_money`.
    - `close_date` comes from `parse_date(synopsis.responseDate)`.
    - Missing keys become `""` or `None`.
    - `description` is stripped of HTML tags.

- [ ] **Step 1: Write the failing tests.**
  - `test_search_pages_until_hitcount`: the fake returns page 1 (2 hits,
    `hitCount=3`) and then page 2 (1 hit). Assert there are 3 results, and that
    the second call's body has `startRecordNum == 2`.
  - `test_search_raises_on_errorcode`: the fake returns `{"errorcode": 1, "msg": "x"}`.
    Assert it raises `GrantsGovError`.
  - `test_fetch_details_parses_fixture`: using `fetch_363715.json`, assert
    `award_ceiling == 5320000`, `award_floor == 50000`, and
    `close_date == date(2027, 1, 31)`.
  - `test_fetch_details_missing_synopsis_fields`: the fake returns
    `{"errorcode":0,"data":{"synopsis":{}}}`. Assert every money and date field
    is `None` and the text fields are `""`.
- [ ] **Step 2: Run the tests and confirm they fail.** Run
  `pytest tests/test_grantsgov.py -v`. Expected: FAIL (ImportError).
- [ ] **Step 3: Implement `src/mag/grantsgov.py` to the interfaces above.**
- [ ] **Step 4: Run the tests and confirm they pass.** Expected: 4 passed.
- [ ] **Step 5: Commit.** Message: `feat: grants.gov client`.

---

### Task 3: Federal collection and classification

**Files:**
- Create: `src/mag/programs.yaml`, `src/mag/federal.py`
- Test: `tests/test_federal.py`

`programs.yaml` holds two lists. The owner can extend both.

```yaml
# Program numbers (CFDA / Assistance Listing) we publish automatically.
allowlist:
  "97.044": {name: "Assistance to Firefighters Grants (AFG / FP&S)", types: [fire, ems]}
  "97.083": {name: "Staffing for Adequate Fire & Emergency Response (SAFER)", types: [fire]}
  "10.766": {name: "USDA Community Facilities", types: [fire, ems]}
# Keyword searches; hits not on the allowlist go to the review queue.
keywords: [firefighter, fire department, emergency medical services, ambulance, fire prevention, rescue squad]
```

**Interfaces:**
- Consumes: `search` and `fetch_details` (Task 2). `Grant` (Task 1).
- Produces:
  - `load_programs(path: Path | None = None) -> dict`. Defaults to the packaged
    `programs.yaml`.
  - `collect_federal(programs: dict, post: Post = http_post) -> tuple[list[Grant], list[Grant]]`.
    Returns `(published, review)`.
    - Runs `search` for each keyword and dedupes hits by `id` (first wins).
    - A hit is published when any `cfdaList` entry is in the allowlist. Its
      `types` are the sorted union of the allowlist entries' types.
    - Other hits go to review, with `types=()`.
    - `fetch_details` is called only for published hits. Review hits use
      search fields only, so the AI budget stays tiny and the API isn't
      hammered.
    - Fields:
      - `id`: `"fed-" + hit["id"]`
      - `title`: `html.unescape(hit["title"])`
      - `url`: `f"https://www.grants.gov/search-results-detail/{hit['id']}"`
      - `status`: `hit["oppStatus"]`
      - `states`: `()`
      - `close_date`: the details value, falling back to the search
        `closeDate`.
    - Results are sorted by `close_date` with `None` last, then by `id`.

- [ ] **Step 1: Write the failing tests.** Use a fake `post` keyed on URL and
  keyword.
  - `test_allowlisted_hit_is_published_with_types`: a hit with
    `cfdaList=["97.044"]` is in `published`, with `types == ("ems","fire")`.
  - `test_non_allowlisted_hit_goes_to_review`: a hit with `cfdaList=["93.142"]`
    is in `review`, and `fetch_details` was not called for it.
  - `test_duplicate_hits_across_keywords_deduped`: the same `id` comes back
    from two keywords, and there is exactly one record.
  - `test_title_entities_unescaped`: `"Victim&rsquo;s &ndash; Program"` becomes
    `"Victim’s – Program"`.
  - `test_forecasted_blank_close_date_ok`: with `closeDate=""` and no
    `responseDate`, `close_date is None` and the result sorts last.
- [ ] **Step 2: Run the tests and confirm they fail.** Run
  `pytest tests/test_federal.py -v`. Expected: FAIL.
- [ ] **Step 3: Implement `federal.py` and add `programs.yaml`.** Make sure
  `programs.yaml` ships as package data (via `pyproject.toml` package-data).
- [ ] **Step 4: Run the tests and confirm they pass.** Expected: 5 passed.
- [ ] **Step 5: Commit.** Message: `feat: federal collection with allowlist and review queue`.

---

### Task 4: Curated state grants (Oklahoma)

**Files:**
- Create: `src/mag/curated.py`, `data/curated/ok.yaml`, `data/curated/README.md`
- Test: `tests/test_curated.py`

**Entry format.** Each YAML file has `state: OK` and `grants: [...]`. Each
entry has these fields:
- Required: `slug`, `title`, `agency`, `url`, `status`, `types`.
- Optional: `open_date`, `close_date`, `award_floor`, `award_ceiling`,
  `eligibility`, `description`.
- `verified` (bool, default `false`).

The README gives the owner a 10-line how-to: copy an entry, fill it in, and
set `verified: true` only after checking the official page.

**Seed `ok.yaml`** with one entry, marked `verified: false`. The owner must
confirm the details on the official site.
- **Oklahoma Rural Fire Defense 80/20 Reimbursement Grant**
  - Agency: Oklahoma Forestry Services
  - URL: `https://forestry.ok.gov/rural-fire-defense-program`
  - Status: `posted`
  - Types: `[fire]`
  - Eligibility: "Cities, towns, fire departments or fire districts serving a
    population under 10,000; equipment purchases or construction; reimbursement
    grant scored on points."
  - Award ceiling: 30000
  - Close date: `2027-09-01` (an unconfirmed annual September 1 pattern)

**Interfaces:**
- Consumes: `Grant`, `parse_date`, `parse_money` (Task 1).
- Produces:
  - `load_curated(directory: Path) -> tuple[list[Grant], list[Grant], list[str]]`.
    Returns `(published, review, errors)`.
    - `id`: `f"{state.lower()}-{slug}"`
    - `source`: `f"state:{state}"`
    - `states`: `(state,)`
    - `verified: true` entries are published, and the rest go to review.
    - An entry missing a required field, or with a `status` or `types` value
      outside the allowed sets, is skipped. It adds an error string containing
      the file name and the slug (or the entry index if there's no slug).
    - Other entries and files still load.

- [ ] **Step 1: Write the failing tests** (using `tmp_path` YAML files).
  - `test_verified_entry_published_unverified_reviewed`
  - `test_missing_required_field_reports_file_and_slug_and_continues`: one bad
    entry and one good entry in the same file. The good one loads, and exactly
    one error mentions `ok.yaml` and the bad slug.
  - `test_invalid_type_rejected`: `types: [police]` produces an error.
  - `test_seed_file_loads_into_review`: the real `data/curated/ok.yaml` gives 0
    published, 1 review, and 0 errors.
- [ ] **Step 2: Run the tests and confirm they fail.** Run
  `pytest tests/test_curated.py -v`. Expected: FAIL.
- [ ] **Step 3: Implement `curated.py` and add the seed file and README.**
- [ ] **Step 4: Run the tests and confirm they pass.** Expected: 4 passed.
- [ ] **Step 5: Commit.** Message: `feat: curated Oklahoma grants with verification flag`.

---

### Task 5: `mag-collect` command

**Files:**
- Create: `src/mag/collect.py`
- Test: `tests/test_collect.py`, `tests/test_live.py` (skipped unless `MAG_LIVE=1`)

**Interfaces:**
- Consumes: `collect_federal`, `load_programs` (Task 3). `load_curated`
  (Task 4). `GrantsGovError` (Task 2).
- Produces:
  - `run(out_dir: Path, curated_dir: Path, post: Post = http_post) -> int`.
    Returns the exit code.
  - `main() -> None`. Arguments: `--out` (default `data`) and `--curated`
    (default `data/curated`). Calls `sys.exit(run(...))`.

**Behavior of `run`:**
- **Writing output.** Writes `out_dir/grants.json` (published, as
  `{"generated": ISO datetime, "grants": [...]}`) and
  `out_dir/review_queue.json` (the same shape, plus an `"errors": [...]`
  list). Each file is written atomically (temp file, then `os.replace`).
- **If the federal step raises `GrantsGovError`:**
  - Keep the previous federal records. These are the `grants.json` and
    `review_queue.json` entries whose `source == "federal"`, if the files
    exist.
  - Add an error `"federal: <message>"`.
  - Still write the curated results.
  - Return 1.
- **Curated errors** go into `errors`, and the run returns 1 if there are any.
- **On success** it returns 0 and prints a summary like
  `published 42 (federal 41, OK 1) | review 17 | errors 0`.

- [ ] **Step 1: Write the failing tests.**
  - `test_run_writes_both_files`: with a fake `post` and the seed curated dir,
    both files exist and parse as JSON, and the exit code is 0.
  - `test_federal_failure_keeps_previous_federal_records`:
    - Pre-write a `grants.json` with one `fed-1` record and one `ok-x` record.
    - The fake `post` raises `GrantsGovError("timeout")`.
    - Assert `fed-1` is still present, `errors` contains `"federal: timeout"`,
      and the exit code is 1.
  - `test_curated_error_sets_exit_code`.
  - `test_live.py::test_live_search_returns_fire_grants`: skipped unless
    `MAG_LIVE=1`. Calls the real `collect_federal` and asserts the call doesn't
    raise and returns lists.
- [ ] **Step 2: Run the tests and confirm they fail.** Run
  `pytest tests/test_collect.py -v`. Expected: FAIL.
- [ ] **Step 3: Implement `collect.py`.**
- [ ] **Step 4: Run the tests and confirm they pass.**
  - Run `pytest -v`. Expected: all pass, with the live test skipped.
  - Run `MAG_LIVE=1 pytest tests/test_live.py -v`. Expected: 1 passed.
- [ ] **Step 5: Real run.** Run `mag-collect`. Expected: the summary line, and
  `data/grants.json` contains at least one `97.044` or `97.083` record if one
  is currently posted or forecasted. Zero is acceptable outside AFG season.
  Report the counts to the owner.
- [ ] **Step 6: Commit.** Message: `feat: mag-collect command`. Commit the
  generated `data/*.json` too, so the owner can look at them.
