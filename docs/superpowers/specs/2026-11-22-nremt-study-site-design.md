# Healthcare Study Platform (working name) — Design

Date: 2026-11-22
Status: Approved by owner 2026-11-22 (name TBD before launch)
Replaces (pauses): `2026-11-22-fire-ems-grant-service-design.md`. The grant
collector code stays in the repo, unused for now.

## Intent and why

This is a **study platform for healthcare students**. EMS (NREMT) is the first
exam it serves; nursing and other programs can be added later. The brand name
must work for all of them, and for an app later.


The owner is a working paramedic who wants an automated side business. Every
NREMT prep app the owner used teaches one way: by-the-book text, boring lecture
videos, and flashcards. The owner learns best when things are explained simply,
in everyday words, with the scientific terms coming after the understanding.
Other students learn differently.

About 27% of EMT and paramedic candidates fail the NREMT on their first try
(the national first-attempt pass rate was about 73% in 2025). Each failure means
a retest fee and a delay before the student can start working.

The product: NREMT-style practice questions where **the value is the "why"**.
When a student misses a question, they get an explanation of why their answer
was wrong and why the right one is right, **taught in the style they chose**.
A follow-up question checks that the idea stuck.

## Goals

- **NREMT-style practice questions.** Original questions (never recalled real
  exam items) that match the exam's format, topics, and difficulty. EMT level
  first; AEMT and Paramedic later.
- **Targeted explanations.** For every wrong answer choice: why that choice is
  wrong, aimed at the specific misunderstanding that leads people to pick it,
  plus why the correct answer is right.
- **Teaching style chosen at signup,** changeable anytime, plus an "explain it
  another way" option on every explanation. Launch styles:
  - **Keep it simple:** everyday words and analogies first, then a "test-day
    words" bridge to the official terms.
  - **Straight to the point:** short, textbook-style.
  - **Real calls:** explained through a patient scenario.
  - **Memory tricks:** mnemonics and shortcuts.
- **Follow-up check.** After a miss, the student gets a follow-up question on
  the same concept.
- **Progress.** Weak topics are tracked, and practice is weighted toward them.
- **Credibility.** The site says "built by a working paramedic." The owner's
  name stays off the site.

## Phases (approach C, built in steps)

1. **Reviewed explanations (launch).** AI drafts questions and every
   wrong-answer explanation in every style. The owner reviews. Students only
   ever see reviewed content. Cheap to run and instant to load.
2. **Live text tutor.** "Still confused?" opens a chat. The tutor answers
   only from the reviewed question, the explanations, and a cited fact sheet
   behind each question. It rephrases; it doesn't invent new medical claims.
   Chat is limited per day.
3. **Voice tutor with characters (premium plan).**
   - The student talks through their reasoning out loud, and an original
     cartoon character tutor listens and explains back by voice.
   - It follows the same rules as the text tutor.
   - It lives on a higher-priced plan, which covers its higher running cost.

## Non-goals (for now)

- A native phone app. The site must work well on phones.
- Lecture videos.
- Practice for the NREMT skills (psychomotor) exam.
- State-specific protocol content.
- AEMT and Paramedic levels, until EMT is live.

## Anti-goals

- **Wrong medical information.** Content must not contradict current
  standard EMS education. Accuracy beats volume.
- **Recalled real exam questions.** Never use recalled real NREMT questions or
  copy other prep products. Original content only.
- **Implying endorsement.** Never imply NREMT endorsement or guarantee a pass.
  The NREMT name may only be used descriptively ("prep for the NREMT exam").
- **Unreviewed medical claims.** In phase 1, students never see an unreviewed
  medical claim. In phases 2–3, the tutor stays grounded in reviewed content.
- **Social media.** The owner on camera or on social media.

## Constraints

- **Budget:** $100 to start.
  - Free hosting tiers.
  - Payments through a payment link (Stripe or similar).
  - Domain: about $12/yr.
  - AI cost is mostly content generation in phase 1, which is a one-time
    cost per question.
- **Owner time:** the owner works a full-time EMS job, so most of the owner's
  time goes to review. The review tool must make checking a question and its
  explanations fast: approve, edit, or reject in one screen.
- **Growth without social media:**
  - **Search:** free topic explainer pages, such as "shock explained simply."
  - **A free sample quiz.**
  - **EMS instructors** sharing it with their classes.
  - **Word of mouth.**

## Approaches considered

- **A — Pre-written, reviewed explanations only.** Safest and cheapest, but no
  follow-up questions.
- **B — Live AI tutor only.** The most personal, but unreviewed medical
  content, and costs grow with usage.
- **C — A first, then a grounded live tutor, then voice.** *Chosen by the
  owner.* It gets accuracy and fast launch from A, and adds the personal tutor
  once revenue exists.

## How it works

### Content pipeline (phase 1)

1. **Blueprint.** A topic list following the NREMT EMT exam's published
   content areas and approximate weights. The areas are airway, respiration and
   ventilation; cardiology and resuscitation; trauma; medical/OB-GYN; and EMS
   operations. The builder confirms the current weights from NREMT's public
   materials.
2. **Fact sheet per concept.** Short, sourced key facts. This is what every
   question and explanation must agree with.
3. **AI drafting.** For each question, AI drafts:
   - the question stem,
   - 4 answer choices,
   - the correct answer,
   - why-wrong text for each distractor,
   - the explanation in all 4 styles,
   - a follow-up question.
4. **Automated self-check.** A second AI pass checks the draft against the
   fact sheet and flags conflicts or ambiguous wording.
5. **Owner review screen.** One question per screen, showing the AI checker's
   flags. The owner approves, edits, or rejects. Only approved items go live.

### Student experience

- **Signup:** pick a level (EMT at launch) and a teaching style.
- **Practice:**
  - Answer a question.
  - On a miss: see why your choice was wrong, then why the right one is right,
    in your style. "Explain another way" switches style.
  - Then a follow-up question on the same concept.
- **Dashboard:** strengths and weak topics, with practice weighted toward the
  weak ones.

### Pricing (Claude's proposal; owner may change)

- **Free:** a sample quiz plus the topic explainer pages.
- **Standard (~$15/month):**
  - all questions,
  - all teaching styles,
  - progress tracking,
  - the live text tutor once phase 2 ships.
- **Premium (~$25/month, phase 3):** adds the voice tutor characters.

## Decided by the owner

- The idea: NREMT prep where the value is the "why you got it wrong"
  explanation.
- Questions in NREMT style.
- The student chooses a teaching style at signup; not everyone wants it simple.
- Approach C in steps.
- The voice tutor with AI characters, as a later step.
- Pause the grant business.
- No social media, no camera, a $100 start, as automated as possible.

## Calls made by Claude (owner may override)

- EMT level first.
- A website, not an app.
- The 4 launch teaching styles listed above.
- The owner reviews every question before launch. Once the AI self-check
  proves reliable, review can move to spot-checks plus anything the checker
  flags.
- Voice goes on a premium plan.
- Prices: ~$15 standard and ~$25 premium.

## Left to the builder

- Tech stack, hosting, and authentication provider (within free tiers).
- AI models and prompts per step. Voice speech-to-text and text-to-speech
  services for phase 3.
- Question count at launch. Target: enough to cover every blueprint area, with
  a few hundred as a first goal.
- Character designs for phase 3 (original characters only).

## Open questions for the owner

1. **Name: to be decided before launch.** Build under a neutral working name.
   Every candidate gets checked for:
   - the domain,
   - Apple App Store and Google Play name conflicts,
   - competitors,
   - US trademarks (tmsearch.uspto.gov).

   Rejected names:
   - **Medic Prep:** EMTprep owns medicprep.com.
   - **Vitals Prep:** an NREMT competitor.
   - **Code 3 Prep:** Code 3 Apps LLC sells EMS exam prep apps.
   - **Names with NREMT or NCLEX in the brand:** trademarks; these may only be
     used descriptively.
2. ~~Pricing?~~ Approved: free sample, ~$15/mo Standard, ~$25/mo Premium (voice).

## Risks

- **A crowded market.** Many low-priced apps exist, and our difference is the
  teaching. Mitigation: free explainer pages and the free sample quiz let
  students feel the difference before paying.
- **Review is a bottleneck.** Owner review hours limit how fast content
  grows. Mitigation: the AI self-check plus a fast review screen; launch
  with fewer, better questions.
- **Medical accuracy.** Mitigation: fact sheets, the self-check, owner
  review, and a "report a problem" button on every question.
- **Live tutor costs (phases 2–3).** Mitigation: daily limits, and voice only
  on the premium plan.
- **Trademark.** Use the NREMT name only descriptively.
