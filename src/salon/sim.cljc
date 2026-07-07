(ns salon.sim
  "Demo driver -- `clojure -M:dev:run`. Walks a clean booking through
  intake -> jurisdiction assessment -> allergy screening -> treatment-
  performance proposal (always escalates) -> human approval -> commit,
  then shows four HARD holds (a jurisdiction with no spec-basis, a
  stale patch test beyond the pre-treatment window, an unresolved
  allergy flag screened directly via `:allergy/screen` [never via the
  actuation op against an unscreened booking -- see this actor's own
  governor ns docstring / the lesson `parksafety`'s ADR-2607071922
  Decision 5, `eldercare`'s, `museum`'s and `conservation`'s ADR-0001s
  already recorded], and a double treatment performance of an already-
  processed booking) that never reach a human at all, and prints the
  audit ledger + the draft treatment-completion records."
  (:require [langgraph.graph :as g]
            [salon.store :as store]
            [salon.operation :as op]))

(def operator {:actor-id "op-1" :actor-role :practitioner :phase 3})

(defn- exec! [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        actor (op/build db)]
    (println "== booking/intake booking-1 (JPN, clean; patch-test 24h ago, allergy-flag resolved) ==")
    (println (exec! actor "t1" {:op :booking/intake :subject "booking-1"
                                :patch {:id "booking-1" :client-name "Sakura Tanaka"}} operator))

    (println "== jurisdiction/assess booking-1 (escalates -- human approves) ==")
    (println (exec! actor "t2" {:op :jurisdiction/assess :subject "booking-1"} operator))
    (println (approve! actor "t2"))

    (println "== allergy/screen booking-1 (clean; escalates -- human approves) ==")
    (println (exec! actor "t3" {:op :allergy/screen :subject "booking-1"} operator))
    (println (approve! actor "t3"))

    (println "== treatment/perform booking-1 (always escalates -- actuation/perform-treatment) ==")
    (let [r (exec! actor "t4" {:op :treatment/perform :subject "booking-1"} operator)]
      (println r)
      (println "-- human practitioner approves --")
      (println (approve! actor "t4")))

    (println "== jurisdiction/assess booking-2 (no spec-basis -> HARD hold) ==")
    (println (exec! actor "t5" {:op :jurisdiction/assess :subject "booking-2" :no-spec? true} operator))

    (println "== jurisdiction/assess booking-3 (escalates -- human approves; sets up the stale-patch-test test) ==")
    (println (exec! actor "t6" {:op :jurisdiction/assess :subject "booking-3"} operator))
    (println (approve! actor "t6"))

    (println "== treatment/perform booking-3 (96h since patch test > 48h -> HARD hold) ==")
    (println (exec! actor "t7" {:op :treatment/perform :subject "booking-3"} operator))

    (println "== allergy/screen booking-4 (unresolved allergy flag -> HARD hold, never reaches a human) ==")
    (println (exec! actor "t8" {:op :allergy/screen :subject "booking-4"} operator))

    (println "== treatment/perform booking-1 AGAIN (double-completion -> HARD hold) ==")
    (println (exec! actor "t9" {:op :treatment/perform :subject "booking-1"} operator))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== draft treatment-completion records ==")
    (doseq [r (store/completion-history db)] (println r))))
