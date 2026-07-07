(ns salon.phase-test
  "The phase table as executable tests. The invariant this repo cannot
  regress on: `:treatment/perform` must NEVER be a member of any
  phase's `:auto` set."
  (:require [clojure.test :refer [deftest is testing]]
            [salon.phase :as phase]))

(deftest treatment-perform-never-auto-at-any-phase
  (testing "structural invariant: no phase, now or in the future entries, auto-commits a real treatment"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :treatment/perform))
          (str "phase " n " must not auto-commit :treatment/perform")))))

(deftest allergy-screen-never-auto-at-any-phase
  (testing "screening carries no direct capital risk, but is still never auto-eligible, matching every sibling KYC/conflict/independence/surveillance/calibration/credential/integrity/patron/authorization/safety-test/inspection/incident-flag/welfare-flag screen"
    (doseq [[n {:keys [auto]}] phase/phases]
      (is (not (contains? auto :allergy/screen))
          (str "phase " n " must not auto-commit :allergy/screen")))))

(deftest phase-0-is-fully-read-only
  (is (empty? (:writes (get phase/phases 0)))))

(deftest phase-3-auto-commits-only-no-capital-risk-ops
  (testing ":booking/intake carries no direct capital risk -- auto-eligible; it is the ONLY auto-eligible op in this domain"
    (is (= #{:booking/intake} (:auto (get phase/phases 3))))))

(deftest gate-hold-always-wins
  (is (= :hold (:disposition (phase/gate 3 {:op :booking/intake} :hold)))))

(deftest gate-escalates-a-clean-non-auto-write
  (is (= :escalate (:disposition (phase/gate 3 {:op :treatment/perform} :commit)))))

(deftest gate-holds-a-write-disabled-in-this-phase
  (is (= :hold (:disposition (phase/gate 0 {:op :booking/intake} :commit)))))
