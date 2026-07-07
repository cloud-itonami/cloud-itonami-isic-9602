# cloud-itonami-isic-9602

Open Business Blueprint for **ISIC Rev.5 9602**: Hairdressing and
other beauty treatment. This repository publishes a hairdressing/
beauty-treatment actor -- client-booking intake, jurisdiction
assessment, allergy screening and treatment performance -- as an OSS
business that any qualified, licensed salon operator can fork, deploy,
run, improve and sell.

Built on this workspace's
[`langgraph-clj`](https://github.com/com-junkawasaki/langgraph-clj)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, Datomic/in-mem checkpoints) -- the same actor pattern as
every prior actor in this fleet
([`cloud-itonami-isic-6511`](https://github.com/cloud-itonami/cloud-itonami-isic-6511),
[`6512`](https://github.com/cloud-itonami/cloud-itonami-isic-6512),
[`6621`](https://github.com/cloud-itonami/cloud-itonami-isic-6621),
[`6622`](https://github.com/cloud-itonami/cloud-itonami-isic-6622),
[`6629`](https://github.com/cloud-itonami/cloud-itonami-isic-6629),
[`6520`](https://github.com/cloud-itonami/cloud-itonami-isic-6520),
[`6530`](https://github.com/cloud-itonami/cloud-itonami-isic-6530),
[`6820`](https://github.com/cloud-itonami/cloud-itonami-isic-6820),
[`6612`](https://github.com/cloud-itonami/cloud-itonami-isic-6612),
[`6492`](https://github.com/cloud-itonami/cloud-itonami-isic-6492),
[`6920`](https://github.com/cloud-itonami/cloud-itonami-isic-6920),
[`6611`](https://github.com/cloud-itonami/cloud-itonami-isic-6611),
[`7120`](https://github.com/cloud-itonami/cloud-itonami-isic-7120),
[`8620`](https://github.com/cloud-itonami/cloud-itonami-isic-8620),
[`8530`](https://github.com/cloud-itonami/cloud-itonami-isic-8530),
[`9200`](https://github.com/cloud-itonami/cloud-itonami-isic-9200),
[`7500`](https://github.com/cloud-itonami/cloud-itonami-isic-7500),
[`9603`](https://github.com/cloud-itonami/cloud-itonami-isic-9603),
[`9521`](https://github.com/cloud-itonami/cloud-itonami-isic-9521),
[`9321`](https://github.com/cloud-itonami/cloud-itonami-isic-9321),
[`8730`](https://github.com/cloud-itonami/cloud-itonami-isic-8730),
[`9102`](https://github.com/cloud-itonami/cloud-itonami-isic-9102),
[`9103`](https://github.com/cloud-itonami/cloud-itonami-isic-9103)) --
the first personal-care-services vertical (ISIC division 96) in this
fleet. Here it is **SalonOps-LLM ⊣ Personal Service Safety Governor**.

> **Why an actor layer at all?** An LLM is great at drafting a
> client-booking summary, normalizing records, and checking whether a
> client's own skin allergy-alert patch test has gone stale relative
> to a bounded pre-treatment window -- but it has **no notion of
> which jurisdiction's practitioner-licensing/chemical-safety
> requirements are official, no license to perform a real chemical or
> skin-piercing treatment on a client, and no way to know on its own
> whether a client's own allergy flag is still unresolved**. Letting
> it perform a treatment directly invites fabricated jurisdiction
> citations, a treatment performed on a stale patch test, and an
> unresolved allergy concern being quietly signed off -- and liability,
> and client-safety risk, for whoever runs it. This project seals the
> SalonOps-LLM into a single node and wraps it with an independent
> **Personal Service Safety Governor**, a human **approval
> workflow**, and an immutable **audit ledger**.

## Scope: what this actor does and does not do

This actor covers client-booking intake through jurisdiction
assessment, allergy screening and treatment performance. It does
**not**, by itself, hold any license required to operate a salon in a
given jurisdiction, and it does not claim to. It also does **not**
model a full salon-management system -- no point-of-sale/inventory
integration, no product-formulation database, no full dermatological
diagnosis (see `salon.registry/max-patch-test-window-hours`'s own
docstring for the honest simplification this makes: a single
representative pre-treatment patch-test-validity window, not a
product-by-product/jurisdiction-by-jurisdiction survey of every
manufacturer's own patch-test protocol). Whoever deploys and operates
a live instance (a licensed salon operator) supplies any jurisdiction-
specific license, the real cosmetology/dermatological expertise and
the real salon-management-system integrations, and bears that
jurisdiction's liability -- the software supplies the governed, spec-
cited, audited execution scaffold so that operator does not have to
build the compliance layer from scratch for every new market.

### Actuation

**Performing a real chemical or skin-piercing treatment is never
autonomous, at any phase, by construction.** Two independent layers
enforce this (`salon.governor`'s `:actuation/perform-treatment` high-
stakes gate and `salon.phase`'s phase table, which never puts
`:treatment/perform` in any phase's `:auto` set) -- see `salon.
phase`'s docstring and `test/salon/phase_test.clj`'s `treatment-
perform-never-auto-at-any-phase`. The actor may draft, check and
recommend; a human licensed practitioner is always the one who
actually performs a treatment. Like `6511`/`6621`/`6629`/`6612`/
`6492`/`7120`/`8620`/`7500`/`9603`/`9321`, this actor has ONE
actuation event.

## The core contract

```
booking intake + jurisdiction facts (salon.facts, spec-cited)
        |
        v
   ┌──────────────┐   proposal      ┌───────────────────────┐
   │ SalonOps-    │ ─────────────▶ │ Personal Service             │  (independent system)
   │ LLM (sealed) │  + citations    │ Safety Governor: spec-basis ·│
   └──────────────┘                 │ evidence-incomplete ·        │
                             commit ◀────┼──────────▶ hold │ patch-test-window-
                                 │             │           │ exceeded (MAXIMUM-
                           record + ledger  escalate ─▶ human   ceiling temporal) ·
                                             (ALWAYS for         allergy-flag-unresolved
                                              :treatment/            (unconditional) ·
                                              perform)                already-completed
```

**The SalonOps-LLM never performs a treatment the Personal Service
Safety Governor would reject, and never does so without a human
sign-off.** Hard violations (fabricated jurisdiction requirements;
unsupported client evidence; a stale patch test beyond the pre-
treatment window; an unresolved allergy flag; a double treatment
performance) force **hold** and *cannot* be approved past; a clean
treatment proposal still always routes to a human.

## Run

```bash
clojure -M:dev:run     # walk one clean lifecycle (treatment performance) + four HARD-hold cases through the actor
clojure -M:dev:test    # governor contract · phase invariants · store parity · registry conformance · facts coverage
clojure -M:lint        # clj-kondo (errors fail; CI mirrors this)
```

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
performs the physical domain work**. Here a facility-hygiene
monitoring robot supports sanitation checks, under the actor, gated by
the independent **Personal Service Safety Governor**. The governor
never dispatches hardware itself; `:high`/`:safety-critical` actions
require human sign-off.

## Open business

This repository is not only source code. It is a public, forkable
business model:

| Layer | What is open |
|---|---|
| OSS core | Actor runtime, Personal Service Safety Governor, treatment-completion draft records, audit ledger |
| Business blueprint | Customer, offer, pricing, unit economics, sales motion |
| Operator playbook | How to fork, license, deploy and support the service in a jurisdiction |
| Trust controls | Governance, security reporting, actuation invariant, audit requirements |

See [`docs/business-model.md`](docs/business-model.md) and
[`docs/operator-guide.md`](docs/operator-guide.md) to start this as an
open business on itonami.cloud, and
[`docs/adr/0001-architecture.md`](docs/adr/0001-architecture.md) for the
full architecture and decision record.

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`9602`). Like `6920`/`7120`/`8620`/`8530`/`9200`/`7500`/`9603`/`9521`/
`9321`/`8730`/`9102`/`9103`, this vertical's booking records are
practice-specific rather than a shared cross-operator data contract,
so `salon.*` runs on the generic identity/forms/dmn/bpmn/audit-ledger
stack only -- no bespoke domain capability lib to reference at all.

## Layout

| File | Role |
|---|---|
| `src/salon/store.cljc` | **Store** protocol -- `MemStore` ‖ `DatomicStore` (`langchain.db`) + append-only audit ledger + treatment-completion history. No dynamically-filed sub-record -- the actuation op acts directly on a pre-seeded booking, and the double-completion guard checks a dedicated `:treatment-completed?` boolean rather than a `:status` value |
| `src/salon/registry.cljc` | Treatment-completion draft records, plus `patch-test-window-exceeded?`/`max-patch-test-window-hours` -- the THIRD check in this fleet's temporal-sufficiency family to enforce a MAXIMUM ceiling, applied to a fresh ground truth (skin allergy-alert patch-test staleness) |
| `src/salon/facts.cljc` | Per-jurisdiction hairdressing/beauty-treatment catalog with an official spec-basis citation per entry, honest coverage reporting |
| `src/salon/salonopsllm.cljc` | **SalonOps-LLM Advisor** -- `mock-advisor` ‖ `llm-advisor`; intake/assessment/allergy-screening/treatment-performance proposals |
| `src/salon/governor.cljc` | **Personal Service Safety Governor** -- 4 HARD checks (spec-basis · evidence-incomplete · patch-test-window-exceeded, pure ground-truth MAXIMUM-ceiling recompute · allergy-flag-unresolved, unconditional evaluation, the THIRTEENTH grounding of this discipline) + already-completed guard + 1 soft (confidence/actuation gate) |
| `src/salon/phase.cljc` | **Phase 0→3** -- read-only → assisted intake → assisted assess → supervised (treatment performance always human; booking intake is the ONLY auto-eligible op, no direct capital risk) |
| `src/salon/operation.cljc` | **OperationActor** -- langgraph-clj StateGraph |
| `src/salon/sim.cljc` | demo driver |
| `test/salon/*_test.clj` | governor contract · phase invariants · store parity · registry conformance · facts coverage |

## Business-process coverage (honest)

This actor covers client-booking intake through jurisdiction
assessment, allergy screening and treatment performance -- the core
governed lifecycle this blueprint's own `docs/business-model.md` names
as its Offer:

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Booking intake + per-jurisdiction hairdressing/beauty-treatment checklisting, HARD-gated on an official spec-basis citation (`:booking/intake`/`:jurisdiction/assess`) | A full salon-management system (point-of-sale/inventory integration, product-formulation database, full dermatological diagnosis -- see `patch-test-window-exceeded?`'s docstring) |
| Allergy screening, evaluated unconditionally so the screening op itself can HARD-hold on its own finding (`:allergy/screen`) | Real salon-management-system integration, marketing/loyalty-program workflows |
| Treatment performance, HARD-gated on the client's own patch test not having gone stale beyond its validity window and a double-completion guard (`:treatment/perform`) | Ongoing product-restocking/scheduling workflows themselves |
| Immutable audit ledger for every intake/assessment/screening/treatment decision | |

Extending coverage is additive: add the next gate (e.g. a product-
recall check) as its own governed op with its own HARD checks and
tests, following the SAME "an independent governor re-verifies against
the actor's own records before any real-world act" pattern this repo's
flagship op already establishes.

## Jurisdiction coverage (honest)

`salon.facts/coverage` reports how many requested jurisdictions
actually have an official spec-basis in `salon.facts/catalog` --
currently 4 seeded (JPN, USA, GBR, DEU) out of ~194 jurisdictions
worldwide. This is a starting catalog to prove the governor contract
end-to-end, not a claim of global coverage. Adding a jurisdiction is
additive: one map entry in `salon.facts/catalog`, citing a real
official source -- never fabricate a jurisdiction's requirements to make
coverage look bigger.

## Maturity

`:implemented` -- `SalonOps-LLM` + `Personal Service Safety Governor`
run as real, tested code (see `Run` above), promoted from the
originally-published `:blueprint`-tier scaffold, modeled closely on
the twenty-three prior actors' architecture. See `docs/adr/0001-
architecture.md` for the history and design.

## License

Code and implementation templates are AGPL-3.0-or-later.
