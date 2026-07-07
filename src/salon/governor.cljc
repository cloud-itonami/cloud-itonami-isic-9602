(ns salon.governor
  "Personal Service Safety Governor -- the independent compliance
  layer that earns the SalonOps-LLM the right to commit. The LLM has
  no notion of jurisdictional practitioner-licensing/chemical-safety
  law, whether a booking's own skin allergy-alert patch test has gone
  stale, whether a booking's own allergy flag is still unresolved, or
  when an act stops being a draft and becomes a real-world chemical or
  skin-piercing treatment, so this MUST be a separate system able to
  *reject* a proposal and fall back to HOLD -- the hairdressing/
  beauty-treatment analog of `cloud-itonami-isic-6512`'s
  CasualtyGovernor.

  Four checks, in priority order, ALL HARD violations: a human
  approver CANNOT override them (you don't get to approve your way
  past a fabricated jurisdiction spec-basis, incomplete licensing
  evidence, a stale patch test, an unresolved allergy flag, or
  performing a treatment on the same booking twice). The confidence/
  actuation gate is SOFT: it asks a human to look (low confidence /
  actuation), and the human may approve -- but see `salon.phase`: for
  `:stake :actuation/perform-treatment` (a real chemical/skin-piercing
  treatment) NO phase ever allows auto-commit either. Two independent
  layers agree that actuation is always a human call.

    1. Spec-basis                  -- did the jurisdiction proposal cite
                                       an OFFICIAL source (`salon.
                                       facts`), or invent one? Like
                                       `clinic.governor`'s/`credit.
                                       governor`'s actuation ops,
                                       `:treatment/perform` acts
                                       directly on a pre-seeded booking
                                       (see `salon.store`'s own
                                       docstring) -- there is no
                                       'booking is missing' failure
                                       mode to guard against here.
    2. Evidence incomplete         -- for `:treatment/perform`, has the
                                       jurisdiction actually been
                                       assessed with a full client-
                                       consent/patch-test/license
                                       evidence checklist on file?
    3. Patch-test window exceeded  -- for `:treatment/perform`,
                                       INDEPENDENTLY recompute whether
                                       the booking's own `:hours-since-
                                       patch-test` exceeds `salon.
                                       registry/max-patch-test-window-
                                       hours` (`salon.registry/patch-
                                       test-window-exceeded?`) -- needs
                                       no proposal inspection or
                                       stored-verdict lookup at all.
                                       The THIRD check in this fleet's
                                       temporal-sufficiency family to
                                       enforce a MAXIMUM ceiling
                                       (`eldercare.governor/care-plan-
                                       review-overdue-violations`
                                       established the first, `museum.
                                       governor/provenance-gap-
                                       exceeds-threshold-violations`
                                       the second).
    4. Allergy flag unresolved     -- reported by THIS proposal itself
                                       (an `:allergy/screen` that just
                                       found an unresolved flag), or
                                       already on file for the booking
                                       (`:allergy/screen`/`:treatment/
                                       perform`). Evaluated
                                       UNCONDITIONALLY (not scoped to a
                                       specific op), the SAME discipline
                                       `casualty.governor/sanctions-
                                       violations`/`marketadmin.
                                       governor/surveillance-flag-
                                       unresolved-violations`/`testlab.
                                       governor/calibration-not-current-
                                       violations`/`clinic.governor/
                                       credential-not-current-
                                       violations`/`registrar.governor/
                                       integrity-flag-unresolved-
                                       violations`/`wagering.governor/
                                       patron-flag-unresolved-
                                       violations`/`veterinary.
                                       governor/credential-not-current-
                                       violations`/`funeral.governor/
                                       authorization-unverified-
                                       violations`/`repairshop.
                                       governor/safety-test-not-passed-
                                       violations`/`parksafety.
                                       governor/inspection-not-passed-
                                       violations`/`eldercare.governor/
                                       incident-flag-unresolved-
                                       violations`/`museum.governor/
                                       incident-flag-unresolved-
                                       violations`/`conservation.
                                       governor/welfare-flag-
                                       unresolved-violations`
                                       established -- the THIRTEENTH
                                       distinct application of this
                                       exact discipline. Like
                                       `parksafety.governor`'s/
                                       `eldercare.governor`'s/`museum.
                                       governor`'s/`conservation.
                                       governor`'s equivalent checks,
                                       this is exercised in tests/demo
                                       via `:allergy/screen` DIRECTLY,
                                       not via the actuation op against
                                       an unscreened booking -- see
                                       this ns's own test suite.
    5. Confidence floor / actuation
       gate                          -- LLM confidence below threshold,
                                       OR the op is `:treatment/
                                       perform` (a REAL, irreversible
                                       act) -> escalate.

  One more guard, double-completion prevention, is enforced but NOT
  listed as a numbered HARD check above because it needs no upstream
  comparison at all -- `already-completed-violations` refuses to
  perform a treatment on the SAME booking twice, off a dedicated
  `:treatment-completed?` fact (never a `:status` value) -- the SAME
  'check a dedicated boolean, not status' discipline `accounting.
  governor`'s/`marketadmin.governor`'s/`testlab.governor`'s/`clinic.
  governor`'s/`registrar.governor`'s/`wagering.governor`'s/
  `veterinary.governor`'s/`funeral.governor`'s/`repairshop.governor`'s/
  `parksafety.governor`'s/`eldercare.governor`'s/`museum.governor`'s/
  `conservation.governor`'s guards establish, informed by
  `cloud-itonami-isic-6492`'s status-lifecycle bug (ADR-2607071320)."
  (:require [salon.facts :as facts]
            [salon.registry :as registry]
            [salon.store :as store]))

(def confidence-floor 0.6)

(def high-stakes
  "Stakes grave enough to always require a human, even when clean.
  Performing a real chemical or skin-piercing treatment is the ONE
  real-world actuation event this actor performs -- a single-member
  set, matching `cloud-itonami-isic-6511`'s/`6621`'s/`6629`'s/`6612`'s/
  `6492`'s/`7120`'s/`8620`'s/`7500`'s/`9603`'s/`9321`'s single-
  actuation shape."
  #{:actuation/perform-treatment})

;; ----------------------------- checks -----------------------------

(defn- spec-basis-violations
  "A `:jurisdiction/assess` (or `:treatment/perform`) proposal with no
  spec-basis citation is a HARD violation -- never invent a
  jurisdiction's practitioner-licensing/chemical-safety requirements."
  [{:keys [op]} proposal]
  (when (contains? #{:jurisdiction/assess :treatment/perform} op)
    (let [value (:value proposal)]
      (when (or (empty? (:cites proposal))
                (and (contains? value :spec-basis) (nil? (:spec-basis value))))
        [{:rule :no-spec-basis
          :detail "公式spec-basisの引用が無い提案は法域要件として扱えない"}]))))

(defn- evidence-incomplete-violations
  "For `:treatment/perform`, the jurisdiction's required client-
  consent/patch-test/license-verification/safety-data-sheet evidence
  must actually be satisfied -- do not trust the advisor's self-
  reported confidence alone."
  [{:keys [op subject]} st]
  (when (= op :treatment/perform)
    (let [b (store/booking st subject)
          assessment (store/assessment-of st subject)]
      (when-not (and assessment
                     (facts/required-evidence-satisfied?
                      (:jurisdiction b) (:checklist assessment)))
        [{:rule :evidence-incomplete
          :detail "法域の必要書類(顧客同意記録/パッチテスト記録/免許確認記録等)が充足していない状態での施術実施提案"}]))))

(defn- patch-test-window-exceeded-violations
  "For `:treatment/perform`, INDEPENDENTLY recompute whether the
  booking's own hours-since-patch-test exceeds `salon.registry/max-
  patch-test-window-hours` via `salon.registry/patch-test-window-
  exceeded?` -- needs no proposal inspection or stored-verdict lookup
  at all, since its input is a permanent ground-truth field already on
  the booking."
  [{:keys [op subject]} st]
  (when (= op :treatment/perform)
    (let [b (store/booking st subject)]
      (when (registry/patch-test-window-exceeded? b)
        [{:rule :patch-test-window-exceeded
          :detail (str subject " のパッチテストから" (:hours-since-patch-test b)
                      "時間経過し、上限(" registry/max-patch-test-window-hours
                      "時間)を超過している")}]))))

(defn- allergy-flag-unresolved-violations
  "An unresolved allergy flag -- reported by THIS proposal (e.g. an
  `:allergy/screen` that itself just found an unresolved flag), or
  already on file in the store for the booking (`:allergy/screen`/
  `:treatment/perform`) -- is a HARD, un-overridable hold. Evaluated
  UNCONDITIONALLY (not scoped to a specific op) so the screening op
  itself can HARD-hold on its own finding."
  [{:keys [op subject]} proposal st]
  (let [hit-in-proposal? (= :unresolved (get-in proposal [:value :verdict]))
        booking-id (when (contains? #{:allergy/screen :treatment/perform} op) subject)
        hit-on-file? (and booking-id (= :unresolved (:verdict (store/allergy-screening-of st booking-id))))]
    (when (or hit-in-proposal? hit-on-file?)
      [{:rule :allergy-flag-unresolved
        :detail "未解決のアレルギーフラグが残っている顧客に対する施術提案は進められない"}])))

(defn- already-completed-violations
  "For `:treatment/perform`, refuses to perform a treatment on the
  SAME booking twice, off a dedicated `:treatment-completed?` fact
  (never a `:status` value)."
  [{:keys [op subject]} st]
  (when (= op :treatment/perform)
    (when (store/booking-already-completed? st subject)
      [{:rule :already-completed
        :detail (str subject " は既に施術実施済み")}])))

(defn check
  "Censors a SalonOps-LLM proposal against the governor rules. Returns
   {:ok? bool :violations [..] :confidence c :escalate? bool :high-stakes? bool
    :hard? bool}."
  [request _context proposal st]
  (let [hard (into []
                   (concat (spec-basis-violations request proposal)
                           (evidence-incomplete-violations request st)
                           (patch-test-window-exceeded-violations request st)
                           (allergy-flag-unresolved-violations request proposal st)
                           (already-completed-violations request st)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (high-stakes (:stake proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :subject    (:subject request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
