(ns salon.registry
  "Pure-function treatment-completion record construction -- an
  append-only salon book-of-record draft.

  Like every sibling actor's registry, there is no single
  international check-digit standard for a treatment-completion
  reference number -- every salon/jurisdiction assigns its own
  reference format. This namespace does NOT invent one; it builds a
  jurisdiction-scoped sequence number and validates the record's
  required fields, the same honest, non-fabricating discipline
  `salon.facts` uses.

  `patch-test-window-exceeded?`/`max-patch-test-window-hours` is the
  THIRD check in this fleet's temporal-sufficiency family to enforce a
  MAXIMUM ceiling (`eldercare.registry/care-plan-review-overdue?`
  established the first, `museum.registry/provenance-gap-exceeds-
  threshold?` the second), applied here to a fresh, domain-authentic
  ground truth: a chemical hair-treatment's own skin allergy-alert
  patch test becomes stale after a bounded pre-treatment window (`48`
  hours is a single representative figure commonly cited in cosmetic-
  industry safety-data-sheet guidance for oxidative hair-colorant skin
  tests, e.g. a 'test 48 hours before every application' instruction),
  not a product-by-product/jurisdiction-by-jurisdiction survey of every
  manufacturer's own patch-test protocol (see `salon.facts`'s own
  docstring for the honest scope this makes).

  This namespace is pure data + pure functions -- no I/O, no network
  call to any real salon-management system. It builds the RECORD a
  salon would keep, not the act of performing the treatment itself
  (that is `salon.operation`'s `:treatment/perform`, always human-
  gated -- see README `Actuation`)."
  (:require [clojure.string :as str]))

(defn- unsigned-certificate
  "Every certificate this actor produces is UNSIGNED -- signature is the
  salon's own act, not this actor's. See README `Actuation`."
  [kind subject record-id]
  {"@context" ["https://www.w3.org/ns/credentials/v2"]
   "type" ["VerifiableCredential" kind]
   "credentialSubject" {"id" subject "record" record-id}
   "proof" nil
   "issued_by_registry" false
   "status" "draft-unsigned"})

(defn- zero-pad [n w]
  (let [s (str n)]
    (str (apply str (repeat (max 0 (- w (count s))) "0")) s)))

(def max-patch-test-window-hours
  "A single representative maximum pre-treatment patch-test-validity
  window -- see ns docstring for the honest simplification this makes
  (not a product-by-product/jurisdiction-by-jurisdiction survey of
  every manufacturer's own patch-test protocol)."
  48)

(defn patch-test-window-exceeded?
  "Does `booking`'s own `:hours-since-patch-test` EXCEED
  `max-patch-test-window-hours`? A pure ground-truth check against the
  booking's own permanent field -- the THIRD check in this fleet's
  temporal-sufficiency family to enforce a MAXIMUM ceiling (see ns
  docstring)."
  [{:keys [hours-since-patch-test]}]
  (and (number? hours-since-patch-test)
       (> hours-since-patch-test max-patch-test-window-hours)))

(defn register-treatment-completion
  "Validate + construct the TREATMENT-COMPLETION registration DRAFT --
  the salon's own legal act of performing a real chemical/skin-
  piercing treatment. Pure function -- does not touch any real salon-
  management system; it builds the RECORD a salon would keep. `salon.
  governor` independently re-verifies the booking's own patch-test
  recency and allergy-flag status, and blocks a double-completion of
  the same booking, before this is ever allowed to commit."
  [booking-id jurisdiction sequence]
  (when-not (and booking-id (not= booking-id ""))
    (throw (ex-info "treatment-completion: booking_id required" {})))
  (when-not (and jurisdiction (not= jurisdiction ""))
    (throw (ex-info "treatment-completion: jurisdiction required" {})))
  (when (< sequence 0)
    (throw (ex-info "treatment-completion: sequence must be >= 0" {})))
  (let [completion-number (str (str/upper-case jurisdiction) "-TRT-" (zero-pad sequence 6))
        record {"record_id" completion-number
                "kind" "treatment-completion-draft"
                "booking_id" booking-id
                "jurisdiction" jurisdiction
                "immutable" true}]
    {"record" record "completion_number" completion-number
     "certificate" (unsigned-certificate "TreatmentCompletion" completion-number completion-number)}))

(defn append [history result]
  (conj (vec history) (get result "record")))
