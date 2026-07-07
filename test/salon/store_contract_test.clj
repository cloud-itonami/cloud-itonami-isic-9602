(ns salon.store-contract-test
  "The Store contract, run against BOTH backends. Proving MemStore and
  the Datomic-backed (langchain.db) store satisfy the same contract is
  what makes 'swap the SSoT for Datomic / kotoba-server' a configuration
  change, not a rewrite -- see `cloud-itonami-isic-6511`'s
  `underwriting.store-contract-test` for the same pattern on the sibling
  actor."
  (:require [clojure.test :refer [deftest is testing]]
            [salon.store :as store]))

(defn- backends []
  [["MemStore" (store/seed-db)] ["DatomicStore" (store/datomic-seed-db)]])

(deftest read-parity
  (doseq [[label s] (backends)]
    (testing label
      (is (= "Sakura Tanaka" (:client-name (store/booking s "booking-1"))))
      (is (= "JPN" (:jurisdiction (store/booking s "booking-1"))))
      (is (= 24 (:hours-since-patch-test (store/booking s "booking-1"))))
      (is (true? (:allergy-flag-resolved? (store/booking s "booking-1"))))
      (is (= 96 (:hours-since-patch-test (store/booking s "booking-3"))))
      (is (false? (:allergy-flag-resolved? (store/booking s "booking-4"))))
      (is (false? (:treatment-completed? (store/booking s "booking-1"))))
      (is (= ["booking-1" "booking-2" "booking-3" "booking-4"]
             (mapv :id (store/all-bookings s))))
      (is (nil? (store/allergy-screening-of s "booking-1")))
      (is (nil? (store/assessment-of s "booking-1")))
      (is (= [] (store/ledger s)))
      (is (= [] (store/completion-history s)))
      (is (zero? (store/next-sequence s "JPN")))
      (is (false? (store/booking-already-completed? s "booking-1"))))))

(deftest write-and-ledger-parity
  (doseq [[label s] (backends)]
    (testing label
      (testing "partial upsert merges, preserving untouched fields"
        (store/commit-record! s {:effect :booking/upsert
                                 :value {:id "booking-1" :client-name "Sakura Tanaka"}})
        (is (= "Sakura Tanaka" (:client-name (store/booking s "booking-1"))))
        (is (= 24 (:hours-since-patch-test (store/booking s "booking-1"))) "unrelated field preserved"))
      (testing "assessment / allergy-screening payloads commit and read back"
        (store/commit-record! s {:effect :assessment/set :path ["booking-1"]
                                 :payload {:jurisdiction "JPN" :checklist ["a" "b"]}})
        (is (= {:jurisdiction "JPN" :checklist ["a" "b"]} (store/assessment-of s "booking-1")))
        (store/commit-record! s {:effect :allergy-screening/set :path ["booking-1"]
                                 :payload {:booking-id "booking-1" :verdict :resolved}})
        (is (= {:booking-id "booking-1" :verdict :resolved} (store/allergy-screening-of s "booking-1"))))
      (testing "treatment completion drafts a completion record and advances the sequence"
        (store/commit-record! s {:effect :booking/mark-completed :path ["booking-1"]})
        (is (= "JPN-TRT-000000" (get (first (store/completion-history s)) "record_id")))
        (is (= "treatment-completion-draft" (get (first (store/completion-history s)) "kind")))
        (is (true? (:treatment-completed? (store/booking s "booking-1"))))
        (is (= 1 (count (store/completion-history s))))
        (is (= 1 (store/next-sequence s "JPN")))
        (is (true? (store/booking-already-completed? s "booking-1")))
        (is (false? (store/booking-already-completed? s "booking-2"))))
      (testing "ledger is append-only and order-preserving"
        (store/append-ledger! s {:op :a :disposition :commit})
        (store/append-ledger! s {:op :b :disposition :hold})
        (is (= [:commit :hold] (mapv :disposition (store/ledger s))))))))

(deftest datomic-empty-store-is-usable
  (let [s (store/datomic-store)]
    (is (nil? (store/booking s "nope")))
    (is (= [] (store/all-bookings s)))
    (is (= [] (store/ledger s)))
    (is (= [] (store/completion-history s)))
    (is (zero? (store/next-sequence s "JPN")))
    (store/with-bookings s {"x" {:id "x" :client-name "n" :hours-since-patch-test 1
                                 :allergy-flag-resolved? true :treatment-completed? false
                                 :jurisdiction "JPN" :status :intake}})
    (is (= "n" (:client-name (store/booking s "x"))))))
