# Question System (Milestone 1) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** AI drafts EMT practice questions from owner-verified fact sheets. An
AI checker and deterministic rules flag problems. The owner approves, edits, or
rejects each question on a phone-friendly review page. Only approved questions
count as content.

**Architecture:**
- A Python package `studyprep` (neutral working name; the brand is TBD) under
  `study/`, separate from the paused grant code in `src/mag`.
- Content lives as reviewable files in git:
  - the blueprint (YAML),
  - fact sheets (YAML),
  - questions (one JSON per question).
- Claude drafts and checks questions through the Anthropic Python SDK with
  structured outputs.
- The owner reviews on a private claude.ai Artifact page with the `db`
  capability:
  - Claude pushes pending questions into the page's database with the
    `ArtifactData` tool.
  - The owner taps Approve, Edit, or Reject.
  - Claude reads the decisions back and applies them with a CLI.

**Tech Stack:** Python 3.13, `anthropic` SDK, `pydantic` v2, PyYAML, pytest.
The review page is a single HTML file using the Artifact runtime `db` and
`user` capabilities. Playwright is pre-installed and used for the page smoke
test.

**Spec:** `docs/superpowers/specs/2026-11-22-nremt-study-site-design.md`
(Goals, Phase 1, "How it works → Content pipeline").

## Global Constraints

- **Students only ever see reviewed content.** Only `status: approved` counts.
- **Original content only.** Never recalled real NREMT questions. "NREMT" is
  used only descriptively.
- **Teaching styles.** Exactly these four style keys:
  - `simple`: everyday words and analogies first, then the test-day terms;
  - `direct`: short, textbook-style;
  - `real_call`: explained through a patient scenario;
  - `memory`: mnemonics and shortcuts.
- **Question shape.** 4 answer choices. Every wrong choice gets a why-wrong
  explanation. Every question has one follow-up question.
- **Level:** EMT only.
- **Model:** `claude-opus-5-5` with `thinking: {type: "adaptive"}`.
  - `output_config.effort` is `"high"` for drafting and `"medium"` for
    checking. The default is `medium`, so set it explicitly.
  - Include server-side refusal fallbacks (`fallbacks: "default"`, beta
    `server-side-fallback-2026-07-01`), and tell the owner they are on.
- **Fact sheets gate drafting.** Generation is refused for any fact sheet
  without `verified: true`.
- **Tests never call the API.** The LLM sits behind a protocol, and tests use
  a fake. A single live smoke test runs only with `STUDY_LIVE=1`.

## Review Focus

1. **The model refuses, returns unparseable output, or hits `max_tokens`.**
   That one question is skipped and reported, the rest of the batch continues,
   and the exit code is nonzero. Tested in Task 3.
2. **Generating from an unverified or missing fact sheet.** Clear error, no API
   call. Tested in Tasks 2 and 3.
3. **An owner edit that makes the question invalid** (e.g., a blank why-wrong or
   duplicate choices). Not approved; status becomes `flagged`, with the
   problems recorded. Tested in Task 4.
4. **Importing a decision for an unknown id, or for a question that is no longer
   pending.** Ignored and reported, never silently re-statused. Tested in
   Task 4.
5. **The review page with zero pending questions, or with `db` unavailable.**
   A clear message, no blank screen or crash. Tested in Task 5.

---

### Task 1: Models, validation, question store

**Files:**
- Create:
  - `study/pyproject.toml`
  - `study/src/studyprep/__init__.py`
  - `study/src/studyprep/models.py`
  - `study/src/studyprep/store.py`
- Test: `study/tests/test_models.py`, `study/tests/test_store.py`

`pyproject.toml`:
- Project name `studyprep`.
- Dependencies: `anthropic`, `pydantic>=2`, `pyyaml`.
- Optional dev dependency: `pytest`.
- Scripts:
  - `study-generate = "studyprep.generate:main"`
  - `study-review = "studyprep.review:main"`
- `[tool.pytest.ini_options] pythonpath=["src"]`.
- Install with `pip install -e 'study[dev]'`.

**Interfaces (Produces):**
- `STYLES = ("simple", "direct", "real_call", "memory")`.
- Pydantic models in `models.py` (all fields required unless noted):
  - `Choice(text: str, why_wrong: str)`. `why_wrong` is `""` for the correct
    choice.
  - `Explanations(simple: str, direct: str, real_call: str, memory: str)`.
  - `FollowUp(stem: str, choices: list[str], correct_index: int, explanation: str)`.
  - `QuestionDraft`, with fields:
    - `stem: str`
    - `choices: list[Choice]`
    - `correct_index: int`
    - `why_right: str`
    - `explanations: Explanations`
    - `test_terms: list[str]`
    - `follow_up: FollowUp`
  - `CheckResult(ok: bool, issues: list[str])`.
  - `HistoryEntry(at: str, action: str, note: str = "")`.
  - `QuestionRecord`, with fields:
    - `id: str`
    - `level: str`
    - `area: str`
    - `concept_id: str`
    - `status: Literal["draft","flagged","approved","rejected"]`
    - `draft: QuestionDraft`
    - `check: CheckResult`
    - `created: str`
    - `history: list[HistoryEntry] = []`
- `validate_draft(d: QuestionDraft) -> list[str]`: human-readable problems;
  empty means valid. The rules:
  - exactly 4 choices;
  - `0 <= correct_index < 4`;
  - choice texts distinct (case- and whitespace-insensitive);
  - every non-correct choice has a non-blank `why_wrong`;
  - `stem`, `why_right`, and all four explanations are non-blank;
  - the follow-up has 4 distinct non-blank choices, a valid `correct_index`,
    and a non-blank explanation and stem;
  - the follow-up stem differs from the main stem.
- In `store.py`:
  - `save(rec: QuestionRecord, root: Path) -> Path`. Writes
    `root/<id>.json` atomically (temp file plus `os.replace`), indented, UTF-8.
  - `load_all(root: Path) -> list[QuestionRecord]`. Sorted by id. Skips
    non-`.json` files.
  - `next_id(concept_id: str, root: Path) -> str`. Returns
    `f"{concept_id}-{n:03d}"`, with `n` = 1 + the highest existing number for
    that concept.

- [ ] **Step 1: Write the failing tests.** Use a `make_draft(**overrides)`
  helper in `tests/conftest.py` that builds a valid draft.
  - `test_valid_draft_has_no_problems`.
  - `test_blank_why_wrong_on_wrong_choice_flagged`. The problem string
    mentions the choice letter "B".
  - `test_duplicate_choices_flagged`. `"Shock"` and `" shock "` count as the
    same.
  - `test_correct_index_out_of_range_flagged`.
  - `test_missing_style_flagged`. `explanations.memory = "  "` is flagged.
  - `test_followup_same_stem_flagged`.
  - `test_store_roundtrip_and_next_id`:
    - save `shock-001` and `shock-002`;
    - `next_id("shock") == "shock-003"`;
    - `next_id("cpr") == "cpr-001"`;
    - `load_all` returns both, equal to the originals.
- [ ] **Step 2: Run the tests and confirm they fail.** Run
  `cd study && pytest -q`. Expected: collection error (import).
- [ ] **Step 3: Implement `models.py` and `store.py` to the interfaces above.**
- [ ] **Step 4: Run the tests and confirm they pass.** Run
  `cd study && pytest -q`. Expected: 7 passed.
- [ ] **Step 5: Commit.** Message: `feat(study): question models, validation, store`.

---

### Task 2: Blueprint and fact sheets

**Files:**
- Create:
  - `study/src/studyprep/content.py`
  - `study/content/blueprint/emt.yaml`
  - `study/content/facts/shock.yaml`
  - `study/content/facts/adult-cpr.yaml`
  - `study/content/facts/anaphylaxis.yaml`
  - `study/content/README.md`
- Test: `study/tests/test_content.py`

**`emt.yaml`.** Use these NREMT EMT content areas and approximate weights. The
executor confirms them against NREMT's public EMT exam outline and records the
result in the ledger.
- `airway`: Airway, Respiration & Ventilation, 18–22
- `cardiology`: Cardiology & Resuscitation, 20–24
- `trauma`: Trauma, 14–18
- `medical`: Medical & Obstetrics/Gynecology, 27–31
- `operations`: EMS Operations, 10–14

Each area lists its concept ids. The seeds:
- `cardiology: [adult-cpr]`
- `medical: [anaphylaxis]`
- `trauma: [shock]`

**Fact sheet format.** Fields:
- `concept_id`
- `title`
- `area`
- `facts`: a list of `{text, source}`
- `verified: false`

**Seed sheets.** 5–8 core facts each, with sources named (for example, AHA
Guidelines for CPR and ECC, or the NREMT/NHTSA National EMS Education
Standards). Leave all three `verified: false`; the owner verifies them.

**README.** The owner's how-to for adding and verifying a fact sheet. It must
say that only `verified: true` sheets can be used to make questions.

**Interfaces (Produces):**
- `Fact(text: str, source: str)`
- `FactSheet(concept_id: str, title: str, area: str, facts: list[Fact], verified: bool = False)`
- `Area(id: str, name: str, weight_min: int, weight_max: int, concepts: list[str])`
- `Blueprint(level: str, areas: list[Area])`
- `load_blueprint(path: Path) -> Blueprint`
- `load_fact_sheet(concept_id: str, facts_dir: Path, require_verified: bool = True) -> FactSheet`.
  Raises `ContentError` with a message naming the file:
  - if the file is missing;
  - on bad YAML or a schema error;
  - if `require_verified` is true and the sheet is not verified. The message
    says to verify the sheet first.
- `class ContentError(Exception)`

- [ ] **Step 1: Write the failing tests.**
  - `test_blueprint_loads_and_weights_sane`: 5 areas, and `weight_min <=
    weight_max` for each.
  - `test_every_blueprint_concept_has_a_fact_sheet`: loaded with
    `require_verified=False`.
  - `test_unverified_sheet_refused`: `ContentError`, and the message contains
    "verify".
  - `test_missing_sheet_error_names_file`.
  - `test_fact_sheet_area_matches_blueprint`.
- [ ] **Step 2: Run the tests and confirm they fail.** Expected: import error.
- [ ] **Step 3: Implement `content.py`.** Write the YAML files and the README.
- [ ] **Step 4: Run the tests and confirm they pass.** Run
  `cd study && pytest -q`. Expected: 12 passed.
- [ ] **Step 5: Commit.** Message: `feat(study): EMT blueprint and seed fact sheets (unverified)`.

---

### Task 3: Drafting, checking, `study-generate`

**Files:**
- Create:
  - `study/src/studyprep/llm.py`
  - `study/src/studyprep/prompts.py`
  - `study/src/studyprep/generate.py`
- Test: `study/tests/test_generate.py`, `study/tests/test_live.py` (skipped
  unless `STUDY_LIVE=1`)

**Interfaces:**
- Consumes:
  - from Task 1: `QuestionDraft`, `CheckResult`, `QuestionRecord`,
    `validate_draft`, `save`, `next_id`;
  - from Task 2: `load_blueprint`, `load_fact_sheet`, `ContentError`.
- Produces:
  - `class LLMError(Exception)`.
  - `class LLM(Protocol)`, with one method:
    `def structured(self, system: str, prompt: str, schema: type[T], effort: str) -> T`.
  - `class ClaudeLLM`, implementing `LLM`:
    - `model="claude-opus-5-5"`, `thinking={"type":"adaptive"}`,
      `max_tokens=16000`, `output_config={"effort": effort}`.
    - Structured output through the SDK `parse` helper with
      `output_format=schema`, reading `.parsed_output`. Per the claude-api
      skill, that is `client.messages.parse(..., output_format=Model)`.
    - Refusal fallbacks: use the beta parse path with
      `betas=["server-side-fallback-2026-07-01"]` and `fallbacks="default"`.
      Before writing it, check that the installed SDK exposes
      `client.beta.messages.parse` and accepts `fallbacks` (a quick `inspect`
      of the installed package). If it doesn't, use `client.messages.parse`
      without fallbacks and ledger a Ruling.
    - Raises `LLMError` when:
      - `stop_reason` is `"refusal"` (include `stop_details.category`);
      - `stop_reason` is `"max_tokens"`;
      - `parsed_output` is `None`;
      - the SDK raises `anthropic.APIError` (wrapped).
  - `draft_question(llm: LLM, sheet: FactSheet) -> QuestionDraft`. Calls
    `llm.structured(prompts.DRAFT_SYSTEM, prompts.draft_prompt(sheet), QuestionDraft, "high")`.
  - `check_question(llm: LLM, draft: QuestionDraft, sheet: FactSheet) -> CheckResult`.
    Uses effort `"medium"`. Merges `validate_draft(draft)` problems into
    `issues`. `ok` is true only if both the model and the validator found no
    issues.
  - `generate(llm: LLM, concept_id: str, count: int, content_dir: Path, questions_dir: Path) -> tuple[list[QuestionRecord], list[str]]`.
    Returns `(saved, errors)`.
    - Loads the blueprint to find the area, and requires a verified sheet. A
      `ContentError` is raised before any `llm` call.
    - Each question: draft, then check, then a `QuestionRecord` with
      `status="draft"` if `check.ok`, else `"flagged"`. `level="EMT"`,
      `created` = UTC ISO, and history `[("created", "")]`. Then `save`.
    - An `LLMError` on one question appends `f"question {i}: {e}"` to `errors`
      and continues.
  - `main()`. Arguments: `--concept`, `--count` (default 3), `--content`
    (default `content`), `--out` (default `content/questions`).
    - Prints `saved N (draft X, flagged Y) | errors E` plus each error.
    - Exit code: 0 if E == 0, 2 on `ContentError`, else 1.

**Prompt requirements** (`prompts.py`; exact copy is the executor's call).
The draft prompt must say all of the following:
- **Fact sheet:** the question must be answerable from, and consistent with,
  the given fact sheet facts (included verbatim).
- **Format:** NREMT style. A single best answer, 4 choices, plausible
  distractors, no "all of the above," and no recalled real exam items.
- **Why-wrong text:** each wrong choice's text names the misconception that
  leads a student to pick it.
- **Explanation styles:**
  - `simple`: everyday words and an analogy, no jargon until a final
    "Test-day words:" line that maps the plain terms to the official terms.
  - `direct`: 2–4 textbook sentences.
  - `real_call`: a short patient scenario.
  - `memory`: a mnemonic or shortcut.
- **Follow-up:** checks the same concept from a different angle.
- **`test_terms`:** lists the official terms used.

The check prompt asks the model to list every factual conflict with the fact
sheet, any ambiguity (more than one defensible answer), and any style that
breaks its rules. `ok` is true only if the list is empty.

- [ ] **Step 1: Write the failing tests.** Use a `FakeLLM` that returns queued
  objects or raises queued `LLMError`s, and records `(schema, effort)` calls.
  Verified sheets are written to `tmp_path`.
  - `test_generate_saves_draft_when_check_ok`: status is `draft`, the file
    exists, and the id is `shock-001`.
  - `test_generate_flags_when_checker_finds_issue`.
  - `test_generate_flags_when_validator_finds_issue_even_if_model_ok`: the fake
    draft has a blank why-wrong, and the model check returns `ok=True`.
  - `test_refusal_on_one_question_continues_batch`: count=3, with the second
    draft raising `LLMError`. Expect 2 saved, 1 error, and the error contains
    "question 2".
  - `test_unverified_sheet_makes_no_llm_calls`: `ContentError` is raised and
    `fake.calls == []`.
  - `test_efforts_used`: the draft call uses `"high"` and the check uses
    `"medium"`.
  - `test_live.py::test_live_draft_and_check`: with `STUDY_LIVE=1` and the
    shock sheet copied to `tmp_path` and marked verified, generate 1 question.
    Assert it was saved, or that a reported `LLMError` names the refusal.
- [ ] **Step 2: Run the tests and confirm they fail.** Expected: import error.
- [ ] **Step 3: Implement `llm.py`, `prompts.py`, and `generate.py`.**
- [ ] **Step 4: Run the tests and confirm they pass.** Run
  `cd study && pytest -q`. Expected: 18 passed, 1 skipped.
- [ ] **Step 5: Live check (needs Anthropic credentials).**
  - Run `ant auth status`. If no credential is active, ask the owner for an
    API key via `request_credential` (host `api.anthropic.com`, header
    `x-api-key`).
  - Run `STUDY_LIVE=1 pytest tests/test_live.py -q`.
  - Report the cost of the run from `usage` to the owner.
  - If no key can be had, ledger it and continue; the unit tests stand.
- [ ] **Step 6: Commit.** Message: `feat(study): AI drafting + self-check + study-generate`.

---

### Task 4: Review sync, `study-review`

**Files:**
- Create: `study/src/studyprep/review.py`
- Test: `study/tests/test_review.py`

**Row format** (the review page and `ArtifactData` both use it). Collection
`questions`, doc id = question id. The data has these fields:
- `id`, `concept_id`, `area`
- `status`: the record status at export (`draft` or `flagged`)
- `issues`: a list of strings
- `stem`
- `choices`: a list of `{letter, text, why_wrong, correct}`
- `why_right`
- `explanations`: `{simple, direct, real_call, memory}`
- `follow_up`: `{stem, choices, correct_letter, explanation}`
- `decision`: `null` | `"approve"` | `"reject"` | `"edit"`
- `note`: str
- `edits`: an object, or `null`

**Allowed `edits` keys** (all values are str):
- `stem`
- `why_right`
- `choice_A.text` … `choice_D.text`
- `choice_A.why_wrong` … `choice_D.why_wrong`
- `explanations.simple`, `explanations.direct`, `explanations.real_call`,
  `explanations.memory`
- `follow_up.stem`, `follow_up.explanation`

**Interfaces:**
- Consumes: from Task 1, `QuestionRecord`, `validate_draft`, `load_all`,
  `save`.
- Produces:
  - `to_row(rec: QuestionRecord) -> dict` (the data above, with
    `decision=None`, `note=""`, `edits=None`).
  - `export_pending(questions_dir: Path) -> list[dict]`. Returns
    `[{"doc_id": id, "data": to_row(rec)}]` for records with status `draft` or
    `flagged`, sorted by id. This is the shape for an `ArtifactData` batch of
    `set` ops.
  - `apply_decisions(rows: list[dict], questions_dir: Path) -> list[str]`.
    Each row is `{"id": ..., "data": {...}}`, as returned by an `ArtifactData`
    list. Returns a report, one line per row that wasn't silently skipped.
    - `decision: null`: skipped, with no report line.
    - Unknown id: `"unknown <id>"`.
    - Record not `draft`/`flagged`: `"<id> already <status>, ignored"`.
    - `approve`: `approved`, if `validate_draft` passes. Otherwise `flagged`
      with the problems.
    - `reject`: `rejected`. The `note` goes in history.
    - `edit`: apply the allowed keys only. An unknown key is an error line and
      the record is unchanged. Then re-validate: valid becomes `approved` with
      history `"edited+approved"`, invalid becomes `flagged`, with the
      problems in `check.issues`.
  - `main()`, with two subcommands:
    - `study-review export [--out review/outbox.json]`: writes the export and
      prints the count.
    - `study-review import FILE`: applies the decisions and prints the report.

- [ ] **Step 1: Write the failing tests.**
  - `test_export_only_pending`: approved and rejected records are excluded.
  - `test_row_marks_correct_choice_and_letters`: letters A–D, with exactly one
    `correct: True`.
  - `test_approve_valid`: becomes `approved`, and history ends with
    `"approved"`.
  - `test_reject_keeps_note`.
  - `test_edit_applies_and_approves`: an edit to `choice_B.why_wrong`.
  - `test_edit_making_invalid_flags_with_problems`: blank
    `choice_B.why_wrong`; the status becomes `flagged` and the issues mention
    B.
  - `test_unknown_edit_key_rejected_record_unchanged`.
  - `test_unknown_id_and_non_pending_reported`: an approved record plus a
    decision gives `"already approved, ignored"`, and the status stays
    approved.
  - `test_null_decision_untouched`.
- [ ] **Step 2: Run the tests and confirm they fail.**
- [ ] **Step 3: Implement `review.py`.**
- [ ] **Step 4: Run the tests and confirm they pass.** Run
  `cd study && pytest -q`. Expected: 27 passed, 1 skipped.
- [ ] **Step 5: Commit.** Message: `feat(study): review export/import`.

---

### Task 5: Phone review page (claude.ai Artifact)

**Files:**
- Create: `study/review/review.html`, `study/tests/test_review_page.py`

**Before writing:**
- Load the `artifact-design` skill.
- Read `0.2.98/claude.d.ts` and `0.2.98/db.d.ts` from the
  `artifact-capabilities` skill directory, and follow them exactly.

**Page behavior:**
- **Capabilities:** `{db: {rules: [{path: "questions", read: "admin", write: "admin"}]}, user: {}}`.
  Only the owner and Editors can read or write.
- **Loading:** subscribe once with `onSnapshot` to the `questions` collection.
  Show the questions whose `decision` is null, one card at a time, with a
  "N left" counter.
  - A 10 s loading timeout shows "Couldn't load questions — reopen the page."
  - `use("db")` returning `null` shows "Open this page in claude.ai while
    signed in."
  - Zero pending shows "All caught up 🎉 — tell Claude to pull your decisions."
- **Card, phone-first:**
  - The stem.
  - The choices, with the correct one marked, and the why-wrong text under each
    wrong choice.
  - Why-right.
  - The 4 style explanations as tabs, labeled Simple / Straight / Real call /
    Memory.
  - The follow-up question.
  - Checker issues in a highlighted box at the top, when present.
- **Buttons:**
  - **Approve:** sets `decision: "approve"`.
  - **Reject:** asks for a short reason (a required text field), then sets
    `decision: "reject"` with `note`.
  - **Edit:** turns the editable fields into textareas. Save writes
    `decision: "edit"` with `edits` holding only the changed keys, named as in
    Task 4.
  - Each button writes one `update` to that doc, then moves to the next card.
  - A failed write keeps the card and shows the error.
- **Theme:** per `artifact-design`: light/dark tokens, 16px gutters, no
  horizontal scroll at 375px width.

- [ ] **Step 1: Write the failing test** (Playwright, `file://` page). Inject a
  stub `window.claude.use` that returns a fake `db` per `db.d.ts`.
  - `test_shows_first_pending_and_counter`: given 2 pending rows and 1 decided
    row, the counter shows "2 left".
  - `test_approve_writes_decision_and_advances`.
  - `test_reject_requires_reason`.
  - `test_edit_sends_only_changed_keys`.
  - `test_empty_state_message`.
  - `test_db_unavailable_message`: `use("db")` returns `null`.
  - `test_no_horizontal_scroll_at_375px`.
- [ ] **Step 2: Run the tests and confirm they fail.** Run
  `cd study && pytest tests/test_review_page.py -q`. Expected: FAIL (file
  missing).
- [ ] **Step 3: Write `review.html`.**
- [ ] **Step 4: Run the tests and confirm they pass.** Expected: 7 passed. Then
  run the full suite: 34 passed, 1 skipped.
- [ ] **Step 5: Publish and wire.** Publish `study/review/review.html` as a
  private Artifact with the capabilities above (icon `checklist`). Then:
  - Generate questions only once the owner has verified at least one fact
    sheet. Until then, push nothing.
  - Do the functional pass from the skill: one `ArtifactData list` of
    `questions`, plus the same read at a lower `as_level` to confirm a viewer
    can't read it.
  - Commit with message `feat(study): phone review page`.
