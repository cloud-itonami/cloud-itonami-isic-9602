(ns salon.store
  "SSoT for the hairdressing/beauty-treatment actor, behind a `Store`
  protocol so the backend is a swap, not a rewrite -- the same seam
  every prior `cloud-itonami-isic-*` actor in this fleet uses:

    - `MemStore`     -- atom of EDN. The deterministic default for
                        dev/tests/demo (no deps).
    - `DatomicStore` -- backed by `langchain.db`, a Datomic-API-compatible
                        EAV store (datalog q / pull / upsert). Pure `.cljc`,
                        so it runs offline AND can be pointed at a real
                        Datomic Local or a kotoba-server pod by swapping
                        `langchain.db`'s `:db-api` (see langchain.kotoba-db).

  Both implement the same protocol and pass the same contract
  (test/salon/store_contract_test.clj), which is the whole point: the
  actor, the Personal Service Safety Governor and the audit ledger
  never know which SSoT they run on.

  Like `credit.store`'s/`accounting.store`'s/`marketadmin.store`'s/
  `testlab.store`'s/`clinic.store`'s simpler entities, a BOOKING is
  acted on directly by the ONE actuation op -- no dynamically-filed
  sub-record, and the double-completion guard checks a dedicated
  `:treatment-completed?` boolean rather than a `:status` value, the
  same discipline `accounting.governor`'s/`marketadmin.governor`'s/
  `testlab.governor`'s/`clinic.governor`'s guards establish.

  The ledger stays append-only on every backend: 'which booking was
  screened for an unresolved allergy flag, which treatment was
  performed, on what jurisdictional basis, approved by whom' is always
  a query over an immutable log -- the audit trail a client trusting a
  salon needs, and the evidence an operator needs if a treatment is
  later disputed."
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [salon.registry :as registry]
            [langchain.db :as d]))

(defprotocol Store
  (booking [s id])
  (all-bookings [s])
  (allergy-screening-of [s booking-id] "committed allergy screening verdict for a booking, or nil")
  (assessment-of [s booking-id] "committed jurisdiction assessment, or nil")
  (ledger [s])
  (completion-history [s] "the append-only treatment-completion history (salon.registry drafts)")
  (next-sequence [s jurisdiction] "next treatment-completion-number sequence for a jurisdiction")
  (booking-already-completed? [s booking-id] "has this booking's treatment already been completed?")
  (commit-record! [s record] "apply a committed op's record to the SSoT")
  (append-ledger! [s fact]   "append one immutable decision fact")
  (with-bookings [s bookings] "replace/seed the booking directory (map id->booking)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained booking set so the actor + tests run
  offline."
  []
  {:bookings
   {"booking-1" {:id "booking-1" :client-name "Sakura Tanaka" :proposed-treatment "oxidative hair color"
                 :hours-since-patch-test 24 :allergy-flag-resolved? true
                 :treatment-completed? false :jurisdiction "JPN" :status :intake}
    "booking-2" {:id "booking-2" :client-name "Atlantis Doe" :proposed-treatment "oxidative hair color"
                 :hours-since-patch-test 24 :allergy-flag-resolved? true
                 :treatment-completed? false :jurisdiction "ATL" :status :intake}
    "booking-3" {:id "booking-3" :client-name "鈴木一郎" :proposed-treatment "oxidative hair color"
                 :hours-since-patch-test 96 :allergy-flag-resolved? true
                 :treatment-completed? false :jurisdiction "JPN" :status :intake}
    "booking-4" {:id "booking-4" :client-name "田中花子" :proposed-treatment "oxidative hair color"
                 :hours-since-patch-test 24 :allergy-flag-resolved? false
                 :treatment-completed? false :jurisdiction "JPN" :status :intake}}})

;; ----------------------------- shared commit logic -----------------------------

(defn- finalize-completion!
  "Backend-agnostic `:booking/mark-completed` -- looks up the booking
  via the protocol and drafts the treatment-completion record, and
  returns {:result .. :booking-patch ..} for the caller to persist."
  [s booking-id]
  (let [b (booking s booking-id)
        seq-n (next-sequence s (:jurisdiction b))
        result (registry/register-treatment-completion booking-id (:jurisdiction b) seq-n)]
    {:result result
     :booking-patch {:treatment-completed? true
                     :completion-number (get result "completion_number")}}))

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (booking [_ id] (get-in @a [:bookings id]))
  (all-bookings [_] (sort-by :id (vals (:bookings @a))))
  (allergy-screening-of [_ id] (get-in @a [:allergy-screenings id]))
  (assessment-of [_ booking-id] (get-in @a [:assessments booking-id]))
  (ledger [_] (:ledger @a))
  (completion-history [_] (:completions @a))
  (next-sequence [_ jurisdiction] (get-in @a [:sequences jurisdiction] 0))
  (booking-already-completed? [_ booking-id] (boolean (get-in @a [:bookings booking-id :treatment-completed?])))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :booking/upsert
      (swap! a update-in [:bookings (:id value)] merge value)

      :assessment/set
      (swap! a assoc-in [:assessments (first path)] payload)

      :allergy-screening/set
      (swap! a assoc-in [:allergy-screenings (first path)] payload)

      :booking/mark-completed
      (let [booking-id (first path)
            {:keys [result booking-patch]} (finalize-completion! s booking-id)
            jurisdiction (:jurisdiction (booking s booking-id))]
        (swap! a (fn [state]
                   (-> state
                       (update-in [:sequences jurisdiction] (fnil inc 0))
                       (update-in [:bookings booking-id] merge booking-patch)
                       (update :completions registry/append result))))
        result)
      nil)
    s)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-bookings [s bookings] (when (seq bookings) (swap! a assoc :bookings bookings)) s))

(defn seed-db
  "A MemStore seeded with the demo booking set. The deterministic
  default."
  []
  (->MemStore (atom (assoc (demo-data)
                           :assessments {} :allergy-screenings {} :ledger [] :sequences {}
                           :completions []))))

;; ----------------------------- DatomicStore (langchain.db) -----------------------------

(def ^:private schema
  "DataScript/Datomic-style schema: only constraint attrs are declared.
  Map/compound values (assessment/allergy-screening payloads, ledger
  facts, completion records) are stored as EDN strings so `langchain.
  db` doesn't expand them into sub-entities -- the same convention
  every sibling actor's store uses."
  {:booking/id                {:db/unique :db.unique/identity}
   :assessment/booking-id     {:db/unique :db.unique/identity}
   :allergy-screening/booking-id {:db/unique :db.unique/identity}
   :ledger/seq                {:db/unique :db.unique/identity}
   :completion/seq            {:db/unique :db.unique/identity}
   :sequence/jurisdiction      {:db/unique :db.unique/identity}})

(defn- enc [v] (pr-str v))
(defn- dec* [s] (when s (edn/read-string s)))

(defn- booking->tx [{:keys [id client-name proposed-treatment hours-since-patch-test
                           allergy-flag-resolved? treatment-completed?
                           jurisdiction status completion-number]}]
  (cond-> {:booking/id id}
    client-name                        (assoc :booking/client-name client-name)
    proposed-treatment                 (assoc :booking/proposed-treatment proposed-treatment)
    hours-since-patch-test             (assoc :booking/hours-since-patch-test hours-since-patch-test)
    (some? allergy-flag-resolved?)     (assoc :booking/allergy-flag-resolved? allergy-flag-resolved?)
    (some? treatment-completed?)       (assoc :booking/treatment-completed? treatment-completed?)
    jurisdiction                       (assoc :booking/jurisdiction jurisdiction)
    status                             (assoc :booking/status status)
    completion-number                  (assoc :booking/completion-number completion-number)))

(def ^:private booking-pull
  [:booking/id :booking/client-name :booking/proposed-treatment :booking/hours-since-patch-test
   :booking/allergy-flag-resolved? :booking/treatment-completed?
   :booking/jurisdiction :booking/status :booking/completion-number])

(defn- pull->booking [m]
  (when (:booking/id m)
    {:id (:booking/id m) :client-name (:booking/client-name m)
     :proposed-treatment (:booking/proposed-treatment m)
     :hours-since-patch-test (:booking/hours-since-patch-test m)
     :allergy-flag-resolved? (boolean (:booking/allergy-flag-resolved? m))
     :treatment-completed? (boolean (:booking/treatment-completed? m))
     :jurisdiction (:booking/jurisdiction m) :status (:booking/status m)
     :completion-number (:booking/completion-number m)}))

(defrecord DatomicStore [conn]
  Store
  (booking [_ id]
    (pull->booking (d/pull (d/db conn) booking-pull [:booking/id id])))
  (all-bookings [_]
    (->> (d/q '[:find [?id ...] :where [?e :booking/id ?id]] (d/db conn))
         (map #(pull->booking (d/pull (d/db conn) booking-pull [:booking/id %])))
         (sort-by :id)))
  (allergy-screening-of [_ id]
    (dec* (d/q '[:find ?p . :in $ ?bid
                :where [?k :allergy-screening/booking-id ?bid] [?k :allergy-screening/payload ?p]]
              (d/db conn) id)))
  (assessment-of [_ booking-id]
    (dec* (d/q '[:find ?p . :in $ ?bid
                :where [?a :assessment/booking-id ?bid] [?a :assessment/payload ?p]]
              (d/db conn) booking-id)))
  (ledger [_]
    (->> (d/q '[:find ?s ?f :where [?e :ledger/seq ?s] [?e :ledger/fact ?f]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (completion-history [_]
    (->> (d/q '[:find ?s ?r :where [?e :completion/seq ?s] [?e :completion/record ?r]] (d/db conn))
         (sort-by first)
         (mapv (comp dec* second))))
  (next-sequence [_ jurisdiction]
    (or (d/q '[:find ?n . :in $ ?j
              :where [?e :sequence/jurisdiction ?j] [?e :sequence/next ?n]]
            (d/db conn) jurisdiction)
        0))
  (booking-already-completed? [s booking-id]
    (boolean (:treatment-completed? (booking s booking-id))))
  (commit-record! [s {:keys [effect path value payload]}]
    (case effect
      :booking/upsert
      (d/transact! conn [(booking->tx value)])

      :assessment/set
      (d/transact! conn [{:assessment/booking-id (first path) :assessment/payload (enc payload)}])

      :allergy-screening/set
      (d/transact! conn [{:allergy-screening/booking-id (first path) :allergy-screening/payload (enc payload)}])

      :booking/mark-completed
      (let [booking-id (first path)
            {:keys [result booking-patch]} (finalize-completion! s booking-id)
            jurisdiction (:jurisdiction (booking s booking-id))
            next-n (inc (next-sequence s jurisdiction))]
        (d/transact! conn
                     [(booking->tx (assoc booking-patch :id booking-id))
                      {:sequence/jurisdiction jurisdiction :sequence/next next-n}
                      {:completion/seq (count (completion-history s)) :completion/record (enc (get result "record"))}])
        result)
      nil)
    s)
  (append-ledger! [s fact]
    (d/transact! conn [{:ledger/seq (count (ledger s)) :ledger/fact (enc fact)}])
    fact)
  (with-bookings [s bookings]
    (when (seq bookings) (d/transact! conn (mapv booking->tx (vals bookings)))) s))

(defn datomic-store
  "A DatomicStore (langchain.db backend) seeded from `data`
  ({:bookings ..}); empty when omitted."
  ([] (datomic-store {}))
  ([{:keys [bookings]}]
   (let [s (->DatomicStore (d/create-conn schema))]
     (with-bookings s bookings))))

(defn datomic-seed-db
  "A DatomicStore seeded with the demo booking set -- the Datomic-
  backed analog of `seed-db`, used to prove protocol parity."
  []
  (datomic-store (demo-data)))
