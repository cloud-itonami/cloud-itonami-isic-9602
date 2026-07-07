# ADR-0001: cloud-itonami-isic-9602 -- SalonOps-LLM as a contained intelligence node

- Status: Accepted (2026-07-07)
- Related: `cloud-itonami-isic-6511`/`6512`/`6621`/`6622`/`6629`/`6520`/
  `6530`/`6820`/`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/
  `7500`/`9603`/`9521`/`9321`/`8730`/`9102`/`9103` ADR-0001s (the
  pattern this ADR ports); ADR-2607071250/ADR-2607071320/
  ADR-2607071351/ADR-2607071618/ADR-2607071640/ADR-2607071654/
  ADR-2607071717/ADR-2607071732/ADR-2607071752/ADR-2607071819/
  ADR-2607071849/ADR-2607071922/ADR-2607072715/ADR-2607072730/
  ADR-2607072745 (`6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/
  `9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/`9103`, the fifteen
  verticals built outside ADR-2607032000's original insurance/real-
  estate batch -- this is the sixteenth)
- Context: Continuing the standing "pick a new ISIC blueprint
  vertical" direction past `9103`, this ADR deepens `cloud-itonami-
  isic-9602` (hairdressing and other beauty treatment) from
  `:blueprint` to `:implemented`, the twenty-fourth actor in this
  fleet -- the FIRST personal-care-services vertical (ISIC division
  96).

## Problem

A salon's treatment-performance workflow bundles several distinct
concerns under one governed workflow:

1. **Jurisdiction practitioner-licensing/chemical-safety correctness**
   -- an official spec-basis citation from a real regulator (厚生労働省/
   California Board of Barbering and Cosmetology/the Health and Safety
   Executive's COSHH regime/the Handwerkskammern), never fabricated.
2. **Patch-test recency** -- has a client's own skin allergy-alert
   patch test gone stale beyond the bounded pre-treatment window
   cosmetic-industry safety-data-sheet guidance commonly requires? The
   THIRD check in this fleet's temporal-sufficiency family to enforce
   a MAXIMUM ceiling (`eldercare.registry/care-plan-review-overdue?`
   established the first, `museum.registry/provenance-gap-exceeds-
   threshold?` the second), applied here to a fresh ground truth
   (patch-test staleness, not a review cadence or a historical-record
   gap).
3. **Allergy-flag resolution verification** -- has a client's own
   allergy flag actually been resolved before a treatment is
   performed? The salon-specific reuse of the unconditional-evaluation
   screening discipline this fleet's `casualty.governor/sanctions-
   violations` originally established -- a THIRTEENTH distinct
   grounding.
4. **Real, high-stakes actuation, once** -- performing a chemical or
   skin-piercing treatment on a real client is a single actuation
   event with direct bodily-safety stakes.

An LLM has no authority or grounding for any of these. The design
problem is therefore not "run a salon with an LLM" but "seal the LLM
inside a trust boundary and layer evidence-sufficiency, patch-test-
recency verification, allergy-flag-resolution verification, audit and
human-approval on top of it, while structurally fixing the one real
actuation event as human-only."

## Decision

### 1. SalonOps-LLM is sealed into the bottom node; it never performs a treatment directly

`salon.salonopsllm` returns exactly four kinds of proposal: intake
normalization, jurisdiction hairdressing/beauty-treatment checklist,
allergy screening, and treatment-performance draft. No proposal writes
the SSoT or commits a real treatment directly.

### 2. OperationActor = langgraph-clj StateGraph, 1 run = 1 salon operation

`salon.operation/build` is the SAME StateGraph shape as every sibling
actor's operation namespace, copied verbatim.

### 3. `patch-test-window-exceeded?` is the THIRD application of the MAXIMUM-ceiling temporal-sufficiency family, on a fresh ground truth

`eldercare.registry/care-plan-review-overdue?` established the FIRST
check in this fleet's temporal-sufficiency family to enforce a MAXIMUM
ceiling, measuring elapsed time since a recurring periodic event.
`museum.registry/provenance-gap-exceeds-threshold?` generalized it to
a documented-history gap. `patch-test-window-exceeded?` is the THIRD
instance, returning to a straightforward "elapsed time since an event"
shape (closer to `eldercare`'s original) but on a genuinely different,
domain-authentic ground truth: a chemical hair-treatment's own skin
allergy-alert patch test becomes stale after a bounded pre-treatment
window (`48` hours, a single representative figure commonly cited in
cosmetic-industry safety-data-sheet guidance).

### 4. Allergy-flag-unresolved screening reuses the unconditional-evaluation discipline for a thirteenth distinct grounding

`allergy-flag-unresolved-violations` reuses `casualty.governor/
sanctions-violations`'s fix (evaluated unconditionally, not scoped to
a specific op, so the screening op itself can HARD-hold on its own
finding) for `:allergy/screen` AND `:treatment/perform` -- the
THIRTEENTH distinct application of this exact discipline in this
fleet.

### 5. The unconditional-evaluation check is tested via the SCREENING op directly, per the lesson already recorded by `parksafety`/`eldercare`/`museum`/`conservation`

`allergy-flag-unresolved-is-held-and-unoverridable` calls `:allergy/
screen` directly against `booking-4` (an unresolved allergy flag), NOT
`:treatment/perform` against an un-screened booking -- because a
failing screen is itself a HARD hold whose payload never persists to
the store, so the actuation op alone could never discover the bad
ground-truth flag through this check family without the screening op
having actually been run first. This build applied that lesson
PROACTIVELY for a fourth consecutive vertical (after `eldercare`,
`museum` and `conservation`), further reinforcing that lessons
recorded in this fleet's ADRs transfer forward reliably.

### 6. Single actuation, matching `6511`/`6621`/`6629`/`6612`/`6492`/`7120`/`8620`/`7500`/`9603`/`9321`'s shape

`salon.governor`'s `high-stakes` set has exactly one member
(`:actuation/perform-treatment`) -- unlike the last three verticals
built (`eldercare`, `museum`, `conservation`, all dual-actuation), this
domain has ONE distinct real-world, bodily-safety-critical act
(performing a chemical or skin-piercing treatment), not two
independently-gated acts. This deliberately restores structural
variety to the fleet rather than defaulting to the dual-actuation
shape by habit.

### 7. Double-completion guard checks a dedicated boolean, not `:status`

`already-completed-violations` checks `:treatment-completed?`, a
dedicated boolean set once and never cleared, rather than a `:status`
value that could legitimately advance past a checked state (the exact
trap `cloud-itonami-isic-6492`'s ADR-0001 documents in detail,
explicitly avoided BY DESIGN in every sibling actor's equivalent guard
since). This actor's `:status` never needs to encode "has this
actuation already happened" at all -- a deliberate architectural
choice applied here for a fourteenth consecutive time.

### 8. No bespoke capability lib

Like `6920`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/
`8730`/`9102`/`9103`, and unlike most other actors in this fleet, this
vertical's booking records are practice-specific rather than a shared
cross-operator data contract -- `salon.*` runs on the generic
identity/forms/dmn/bpmn/audit-ledger stack only, per the blueprint's
own explicit statement.

## Consequences

- (+) Personal-care services get the same governed, auditable-actor
  treatment as the twenty-three prior actors, extending the pattern to
  a genuinely different economic sector (personal-care services, ISIC
  division 96) for the first time.
- (+) `patch-test-window-exceeded?` is a genuine structural
  contribution: a third instance of the MAXIMUM-ceiling temporal-
  sufficiency family applied to a fresh, domain-authentic ground
  truth.
- (+) The actuation invariant (governor + phase, two layers) is
  regression-tested by `test/salon/phase_test.clj`'s `treatment-
  perform-never-auto-at-any-phase`.
- (+) `MemStore` ‖ `DatomicStore` parity is proven by `test/salon/
  store_contract_test.clj`, the same `:db-api`-driven swap pattern
  every sibling actor uses.
- (+) This build deliberately returns to a SINGLE-actuation shape
  after three consecutive dual-actuation builds, restoring structural
  variety to the fleet rather than defaulting to one shape by habit.
- (+) The allergy-flag-unresolved test/demo again correctly applied
  the established SCREENING-op-directly pattern for a fourth
  consecutive vertical after `eldercare`, `museum` and `conservation`
  -- further evidence that lessons recorded in this fleet's ADRs
  continue to transfer forward reliably.
- (-) This R0 seeds only 4 jurisdictions (JPN, USA, GBR, DEU) with an
  official spec-basis, out of ~194 worldwide; `salon.facts/coverage`
  reports this honestly rather than claiming broader coverage.
- (-) `patch-test-window-exceeded?` models only a single representative
  pre-treatment patch-test-validity window (48 hours), not a product-
  by-product/jurisdiction-by-jurisdiction survey of every
  manufacturer's own patch-test protocol, nor a full salon-management
  system (point-of-sale/inventory integration, product-formulation
  database, full dermatological diagnosis are out of scope -- see that
  fn's own docstring); real salon-management-system integration and
  ongoing product-restocking/scheduling workflows are all out of scope
  for this OSS actor -- each operator's responsibility (see README's
  coverage table).
- 30 tests / 126 assertions, lint clean.

## Alternatives considered

| Option | Verdict | Reason |
|---|---|---|
| Add this as an addendum to any prior post-batch ADR | ❌ | All fifteen of those ADRs' titles and scopes are explicitly `cloud-itonami-isic-6612`/`6492`/`6920`/`6611`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/`9321`/`8730`/`9102`/`9103`; mixing a different ISIC division (96, distinct from all of those fifteen's divisions) into any would blur scope boundaries |
| Keep `cloud-itonami-isic-9602` at `:blueprint` only | ❌ | The standing direction continues past `9103`; personal-care services are a natural, well-precedented next domain, further diversifying this fleet into a sector not yet touched |
| Continue the dual-actuation shape (invent a second actuation op) for this domain | ❌ | The blueprint itself explicitly names ONE actuation concern ("performing a chemical or skin-piercing treatment"); inventing a second would misrepresent the domain just to match recent builds' shape -- honest single-actuation modeling, matching the blueprint's own stated scope, is correct here |
| Test `allergy-flag-unresolved-violations` via the actuation op against an un-screened booking (the shape `parksafety`'s ORIGINAL, buggy test used) | ❌ | Already proven wrong by `parksafety`'s ADR-2607071922 Decision 5 and reconfirmed by `eldercare`'s, `museum`'s and `conservation`'s ADR-0001s -- a failing screen never persists its payload to the store, so the actuation op alone cannot discover the bad ground-truth flag through this check family; this build tested the SCREENING op directly from the start |
| Reference a capability lib (e.g. a hypothetical `kotoba-lang/salon`) for consistency with most prior actors | ❌ | The blueprint itself explicitly states this vertical's records are practice-specific, not a shared cross-operator contract -- inventing a capability lib reference where the blueprint says none exists would misrepresent the domain, the same reasoning established by every "no bespoke capability lib" sibling's ADR |
