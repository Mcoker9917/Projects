# Fire/EMS Grant Service — Design

Date: 2026-11-22
Status: Draft, awaiting owner approval

## Intent and why

The owner is a working paramedic who wants a side business that is as automated
as possible and earns real money. Small fire departments and EMS agencies,
most of them volunteer or mostly volunteer (about 85% of ~27,000 registered US
departments), miss grant money for two reasons the owner has seen first-hand:

1. They don't know the grants exist. Federal, state, and private grants are
   scattered across dozens of websites with different deadlines.
2. Their applications aren't strong enough. Nobody on staff has the time or
   skill to write a competitive narrative.

The service fixes both: free discovery and scoring to get found, paid
application drafting to make money.

## Goals

- **Free grant listing website.** One page per grant: who qualifies, how much,
  deadline, what it pays for. These pages are how people find the service
  through search.
- **Free email alerts.** Subscribers pick their state and their type (fire, EMS,
  or both). They get new grants and deadline reminders.
- **Free application score.** A department pastes its draft and gets a
  section-by-section score against that grant's published criteria, with
  specific fixes.
- **Paid application draft (~$149).** The department fills in a questionnaire
  with its real numbers. A drafting bot writes the application, a reviewer bot
  scores it and sends it back for fixes until it passes a threshold, and the
  department reviews and submits it itself.
- **Ready before the next FEMA AFG window.** The last one ran May 19 – June 22,
  2026. Assume a similar spring 2027 window. Free tools should be live by about
  February 2027 so search engines have time to find them.

## Non-goals (for now)

- Paid alert tier (maybe later).
- Submitting applications on a department's behalf.
- Grant management after an award (reporting, compliance).
- Non-fire/EMS customers.

## Anti-goals (failure even if it "works")

- **Spam outreach.** No mass contact forms or bulk cold email. Outreach is
  limited to low-volume, personal emails from the owner.
- **Promising funding.** Never promise or imply guaranteed funding. Present the
  score as "how well this matches what reviewers look for," not as odds of
  winning.
- **Inventing facts.** No fabricated facts in applications. Drafts use only
  numbers the department supplies; missing facts are flagged, never invented.
- **Contingency fees.** No fees tied to the award amount (many grant programs
  forbid this). Flat fee only.
- **The owner on camera or on social media.**

## Constraints

- **Budget:** $100 to start. Free tiers wherever possible. Paid costs at first:
  a domain (~$12/yr) and AI usage.
- **Owner time:** a few hours a week, alongside a full-time EMS job.
- **No license required.** Nothing in the service needs one.
- **No social media, no cold calling.** Growth comes from search, free tools that
  get forwarded, state association partnerships, word of mouth, and the owner's
  occasional personal emails.

## Approaches considered

- **A — Cheap alerts ($19/mo) plus paid application help.** Recurring income, but
  broke volunteer departments still face a monthly fee.
- **B — One all-in-one subscription ($49/mo).** Simple to sell, but heavy users
  eat review time under a flat fee, and $49/month is a harder sell for tiny
  departments.
- **C — Free alerts, listing, and score; paid application drafts.** *Chosen.*
  "Free" is the best way to grow without social media: chiefs forward alerts,
  and search finds the grant pages and the scorer. It's also the most automated,
  because only paid drafts get any human touch. The trade-off is lumpy income
  that peaks around deadlines.

Competitors for reference: FireGrantsHelp and GrantFinder charge $89/mo for
search and alerts. First Responder Grants sells pricier consulting.

## How it works

### 1. Grant collection (automated, runs daily)
- **Federal:** Grants.gov / simpler.grants.gov public data, filtered to
  fire/EMS-relevant programs (AFG, SAFER, FP&S, USDA rural programs, and
  others).
- **State:** each state EMS office and fire marshal grant page. Start with the
  owner's state plus a handful of large or rural states, then expand.
- **Private:** corporate and foundation programs (start with a curated
  hand-made list).
- **New and changed grants:** an AI step pulls out the eligibility, amount,
  deadline, and allowed uses into a standard record.
- **Unclear grants:** low-confidence records wait in a short review queue for
  the owner and are not published until checked.

### 2. Website (static, free hosting)
- One page per grant, plus pages by state and by type, plus pages for the
  free scorer and for ordering.
- Rebuilt automatically when the grant data changes.

### 3. Email alerts
- Free email service tier. Weekly digest plus deadline reminders (30 and 7 days
  out), filtered by each subscriber's choices.

### 4. Criteria research (per grant program)
- For each major program, a research step reads the official funding notice
  and guidance and writes a scoring rubric: sections, what reviewers weigh, and
  common mistakes.
- Rubrics are stored and reused. They are refreshed when a new funding notice
  comes out.

### 5. Free scorer
- The user picks a grant and pastes a draft. The reviewer bot scores each
  rubric section and gives specific fixes.
- Rate-limited (e.g., a few per email address per day, with a daily cap) to
  protect the AI budget.
- Ends with a soft offer: "Want us to draft a stronger version? $149."

### 6. Paid drafting pipeline
1. Payment through a payment link (Stripe or similar).
2. The department fills in a questionnaire tailored to the grant's rubric:
   budget, call volume, equipment age, coverage area, and so on.
3. The drafting bot writes the application.
4. The reviewer bot scores it. Below the threshold, it goes back with notes
   (limited number of rounds). Missing facts trigger questions to the
   customer, never invented facts.
5. The owner spot-checks. At first the owner checks every draft. Once scores
   prove reliable, only borderline drafts.
6. The draft is delivered as an editable document with its scorecard. The
   department reviews and submits.

## Decided by the owner

- Option C business model.
- The reviewer bot with deep research and scoring.
- No social media, no on-camera work, no cold calling, no license, $100 start.

## Calls made by Claude (owner may override)

- **Price:** $149 per draft, flat fee.
- **Score wording:** the score measures fit to the published criteria, not
  odds of funding.
- **Sources:** start with federal programs plus a few states, not all 50 at
  once.
- **Hosting:** static site on free hosting. Free email service tier. Payment
  links instead of a custom checkout.
- **Abuse protection:** the free scorer is rate-limited.
- **Owner time:** the owner's regular work is the low-confidence grant review
  queue plus draft spot-checks.

## Left to the builder

- Specific libraries, hosting provider, and email provider (within free tiers).
- AI model choice and prompt design per step.
- Scraper approach per state site.
- Exact score threshold and number of revision rounds (tune on test drafts).

## Open questions for the owner

1. Which state are you in? It goes first in the state coverage, and you can
   check that the listings are accurate.
2. Business name, and whether to say "built by a working paramedic" on the
   site. Your name is optional; the paramedic part builds trust.
3. Which email address should receive orders and customer questions?

## Risks

- **State pages break:** scrapers fail when state sites change. Mitigation:
  the daily run reports failures, and a missing source shows up in the
  review queue.
- **Overconfident scores:** customers may read a score as odds of winning.
  Mitigation: wording, plus calibration against any public winning examples.
- **Lumpy income:** most money will come in near deadlines. Mitigation: build
  the free list early so each deadline season has a bigger audience.
- **AI costs:** a free scorer could be abused. Mitigation: rate limits and a
  daily cap.
