# Curated state grants

One file per state (`ok.yaml` = Oklahoma). Grants here only go on the website
once you have checked them.

To add a grant:
1. Copy an existing entry (from `- slug:` down to `verified:`).
2. Give it a new short `slug` (lowercase, dashes, e.g. `ems-equipment-2027`).
3. Fill in title, agency, official url, status (forecasted/posted/closed/rolling),
   types ([fire], [ems] or [fire, ems]), and any amounts/dates you know.
4. Open the official page and confirm the details.
5. Only then change `verified: false` to `verified: true`.

If something is wrong with an entry, the collector skips it and tells you which one.
