(ns salon.registry-test
  (:require [clojure.test :refer [deftest is]]
            [salon.registry :as r]))

;; ----------------------------- patch-test-window-exceeded? -----------------------------

(deftest window-not-exceeded-when-within-hours
  (is (not (r/patch-test-window-exceeded? {:hours-since-patch-test 24})))
  (is (not (r/patch-test-window-exceeded? {:hours-since-patch-test 48}))
      "exactly at the ceiling is not yet exceeded"))

(deftest window-exceeded-when-over-hours
  (is (r/patch-test-window-exceeded? {:hours-since-patch-test 49}))
  (is (r/patch-test-window-exceeded? {:hours-since-patch-test 96})))

(deftest window-exceeded-is-false-on-missing-or-non-numeric-field
  (is (not (r/patch-test-window-exceeded? {})))
  (is (not (r/patch-test-window-exceeded? {:hours-since-patch-test nil})))
  (is (not (r/patch-test-window-exceeded? {:hours-since-patch-test "96"}))))

;; ----------------------------- register-treatment-completion -----------------------------

(deftest treatment-completion-is-a-draft-not-a-real-treatment
  (let [result (r/register-treatment-completion "booking-1" "JPN" 0)]
    (is (nil? (get-in result ["certificate" "proof"])))
    (is (= (get-in result ["certificate" "issued_by_registry"]) false))
    (is (= (get-in result ["certificate" "status"]) "draft-unsigned"))))

(deftest treatment-completion-assigns-completion-number
  (let [result (r/register-treatment-completion "booking-1" "JPN" 7)]
    (is (= (get result "completion_number") "JPN-TRT-000007"))
    (is (= (get-in result ["record" "booking_id"]) "booking-1"))
    (is (= (get-in result ["record" "kind"]) "treatment-completion-draft"))
    (is (= (get-in result ["record" "immutable"]) true))))

(deftest treatment-completion-validation-rules
  (is (thrown? Exception (r/register-treatment-completion "" "JPN" 0)))
  (is (thrown? Exception (r/register-treatment-completion "booking-1" "" 0)))
  (is (thrown? Exception (r/register-treatment-completion "booking-1" "JPN" -1))))

(deftest completion-history-is-append-only
  (let [c1 (r/register-treatment-completion "booking-1" "JPN" 0)
        hist (r/append [] c1)
        c2 (r/register-treatment-completion "booking-2" "JPN" 1)
        hist2 (r/append hist c2)]
    (is (= 2 (count hist2)))
    (is (= "JPN-TRT-000000" (get-in hist2 [0 "record_id"])))
    (is (= "JPN-TRT-000001" (get-in hist2 [1 "record_id"])))))
