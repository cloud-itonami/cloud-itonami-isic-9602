(ns salon.governor-contract-test
  "The governor contract as executable tests -- the hairdressing/
  beauty-treatment analog of `cloud-itonami-isic-6512`'s `casualty.
  governor-contract-test`. The single invariant under test:

    SalonOps-LLM never performs a treatment the Personal Service
    Safety Governor would reject, `:treatment/perform` NEVER auto-
    commits at any phase, `:booking/intake` (no direct capital risk)
    MAY auto-commit when clean, and every decision (commit OR hold)
    leaves exactly one ledger fact."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [salon.store :as store]
            [salon.operation :as op]))

(defn- fresh []
  (let [db (store/seed-db)]
    [db (op/build db)]))

(def operator {:actor-id "op-1" :actor-role :practitioner :phase 3})

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn- assess!
  "Walks `subject` through assess -> approve, leaving an assessment on
  file. Uses distinct thread-ids per call site by suffixing
  `tid-prefix`."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-assess") {:op :jurisdiction/assess :subject subject} operator)
  (approve! actor (str tid-prefix "-assess")))

(defn- screen!
  "Walks `subject` through allergy screening -> approve, leaving a
  screening on file. Only safe to call for a booking whose allergy
  flag is already resolved -- an unresolved flag HARD-holds the screen
  itself (see `allergy-flag-unresolved-is-held-and-unoverridable`)."
  [actor tid-prefix subject]
  (exec-op actor (str tid-prefix "-screen") {:op :allergy/screen :subject subject} operator)
  (approve! actor (str tid-prefix "-screen")))

(deftest clean-intake-auto-commits
  (let [[db actor] (fresh)
        res (exec-op actor "t1"
                  {:op :booking/intake :subject "booking-1"
                   :patch {:id "booking-1" :client-name "Sakura Tanaka"}} operator)]
    (is (= :commit (get-in res [:state :disposition])))
    (is (= "Sakura Tanaka" (:client-name (store/booking db "booking-1"))) "SSoT actually updated")
    (is (= 1 (count (store/ledger db))))))

(deftest jurisdiction-assess-always-needs-approval
  (testing "assess is never in any phase's :auto set -- always human approval, even when clean"
    (let [[db actor] (fresh)
          res (exec-op actor "t2" {:op :jurisdiction/assess :subject "booking-1"} operator)]
      (is (= :interrupted (:status res)))
      (let [r2 (approve! actor "t2")]
        (is (= :commit (get-in r2 [:state :disposition])))
        (is (some? (store/assessment-of db "booking-1")))))))

(deftest fabricated-jurisdiction-is-held
  (testing "a jurisdiction/assess proposal with no official spec-basis -> HOLD, never reaches a human"
    (let [[db actor] (fresh)
          res (exec-op actor "t3"
                    {:op :jurisdiction/assess :subject "booking-1" :no-spec? true} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:no-spec-basis} (-> (store/ledger db) first :basis)))
      (is (nil? (store/assessment-of db "booking-1")) "no assessment written"))))

(deftest treatment-perform-without-assessment-is-held
  (testing "treatment/perform before any jurisdiction assessment -> HOLD (evidence incomplete)"
    (let [[db actor] (fresh)
          res (exec-op actor "t4" {:op :treatment/perform :subject "booking-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:evidence-incomplete} (-> (store/ledger db) first :basis))))))

(deftest patch-test-window-exceeded-is-held
  (testing "a booking whose hours-since-patch-test exceeds the max-patch-test-window-hours ceiling -> HOLD"
    (let [[db actor] (fresh)
          _ (assess! actor "t5pre" "booking-3")
          res (exec-op actor "t5" {:op :treatment/perform :subject "booking-3"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:patch-test-window-exceeded} (-> (store/ledger db) last :basis)))
      (is (empty? (store/completion-history db))))))

(deftest allergy-flag-unresolved-is-held-and-unoverridable
  (testing "an unresolved allergy flag on a booking -> HOLD, and never reaches request-approval -- exercised via :allergy/screen DIRECTLY, not via the actuation op against an unscreened booking (see this actor's governor ns docstring / parksafety's ADR-2607071922 Decision 5 / eldercare's, museum's and conservation's ADR-0001s)"
    (let [[db actor] (fresh)
          res (exec-op actor "t6" {:op :allergy/screen :subject "booking-4"} operator)]
      (is (= :hold (get-in res [:state :disposition])) "settles immediately, no interrupt")
      (is (not= :interrupted (:status res)))
      (is (some #{:allergy-flag-unresolved} (-> (store/ledger db) first :basis)))
      (is (nil? (store/allergy-screening-of db "booking-4")) "no clearance written"))))

(deftest treatment-perform-always-escalates-then-human-decides
  (testing "a clean, fully-assessed, patch-test-current, allergy-clear booking still ALWAYS interrupts for human approval -- actuation/perform-treatment is never auto"
    (let [[db actor] (fresh)
          _ (assess! actor "t7pre" "booking-1")
          _ (screen! actor "t7pre2" "booking-1")
          r1 (exec-op actor "t7" {:op :treatment/perform :subject "booking-1"} operator)]
      (is (= :interrupted (:status r1)) "pauses for human approval even when governor-clean")
      (testing "approve -> commit, completion record drafted"
        (let [r2 (approve! actor "t7")]
          (is (= :commit (get-in r2 [:state :disposition])))
          (is (true? (:treatment-completed? (store/booking db "booking-1"))))
          (is (= 1 (count (store/completion-history db))) "one draft completion record"))))))

(deftest treatment-perform-double-completion-is-held
  (testing "performing a treatment on the same booking twice -> HOLD on the second attempt"
    (let [[db actor] (fresh)
          _ (assess! actor "t8pre" "booking-1")
          _ (screen! actor "t8pre2" "booking-1")
          _ (exec-op actor "t8a" {:op :treatment/perform :subject "booking-1"} operator)
          _ (approve! actor "t8a")
          res (exec-op actor "t8" {:op :treatment/perform :subject "booking-1"} operator)]
      (is (= :hold (get-in res [:state :disposition])))
      (is (some #{:already-completed} (-> (store/ledger db) last :basis)))
      (is (= 1 (count (store/completion-history db))) "still only the one earlier completion"))))

(deftest every-decision-leaves-one-ledger-fact
  (testing "write-only-through-ledger: N operations -> N ledger facts"
    (let [[db actor] (fresh)]
      (exec-op actor "a" {:op :booking/intake :subject "booking-1"
                          :patch {:id "booking-1" :client-name "Sakura Tanaka"}} operator)
      (exec-op actor "b" {:op :jurisdiction/assess :subject "booking-1" :no-spec? true} operator)
      (is (= 2 (count (store/ledger db)))
          "one commit + one hold, both recorded"))))
