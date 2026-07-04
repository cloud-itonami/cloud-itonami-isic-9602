# Business Model: Hairdressing and other beauty treatment

## Classification

- Repository: `cloud-itonami-isic-9602`
- ISIC Rev.5: `9602`
- Activity: hairdressing and other beauty treatment -- hair, skin and nail services for customers by licensed practitioners
- Social impact: community access, data sovereignty, transparent audit

## Customer

- independent salons/spas
- cooperative beauty-service collectives
- community wellness programs

## Offer

- client booking intake
- service/treatment-plan proposal
- treatment-completion proposal
- immutable audit ledger

## Revenue

- self-host setup: one-time implementation fee
- managed hosting: monthly subscription per salon
- support: monthly retainer with SLA
- migration: import from an incumbent salon-booking system
- per-service fee

## Trust Controls

- no chemical or skin-piercing treatment is performed without human sign-off (a licensed practitioner)
- a fabricated allergy/patch-test record forces a hold, not an override
- every treatment path is auditable
- client health data stays outside Git
- emergency manual override paths remain outside LLM control
