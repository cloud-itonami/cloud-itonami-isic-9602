(ns salon.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.
  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave5 rollout ledger). Drives the REAL actor stack (salon.operation ->
  salon.governor -> salon.store) through a scenario built from real
  seeded demo data (`salon.store/demo-data`). No invented numbers, no
  timestamps, byte-identical across reruns against the same seed.

  The scenario below mirrors `salon.sim`'s own demo (independently
  verified against `salon.store`'s real seed ids before this ns was
  written -- every subject id used here exists in `demo-data`, and
  every HARD-hold reason below is a real `salon.governor` rule, not an
  invented one):
    - booking-1 (JPN, patch-test 24h ago, allergy flag resolved) walks
      the clean path: an auto-committing `:booking/intake`, then two
      approval-gated writes, then the always-escalating
      `:treatment/perform` actuation, each approved by the human
      operator.
    - booking-2's jurisdiction is asserted with no official spec-basis
      -> HARD hold `:no-spec-basis` (salon.governor/spec-basis-violations).
    - booking-3 is assessed and approved first (so evidence IS on file),
      then its `:treatment/perform` proposal HARD holds anyway because
      its own `:hours-since-patch-test` (96h) exceeds
      `salon.registry/max-patch-test-window-hours` (48h) ->
      `:patch-test-window-exceeded` (salon.governor/patch-test-window-
      exceeded-violations, independently recomputed from the booking's
      own ground-truth field).
    - booking-4 has `:allergy-flag-resolved? false` on file -> its
      `:allergy/screen` HARD holds -> `:allergy-flag-unresolved`
      (salon.governor/allergy-flag-unresolved-violations), never
      reaching a human at all."
  (:require [clojure.string :as str]
            [salon.store :as store]
            [salon.operation :as op]
            [langgraph.graph :as g]))

(def ^:private operator
  {:actor-id "op-1" :actor-role :salon-manager :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}} {:thread-id tid :resume? true}))

(defn run-demo!
  "Drives `salon.operation` through a real actor run against a real
  `salon.store/seed-db`. Returns the seeded+mutated store so `render`
  can read every table straight off it. See ns docstring for the full
  scenario rationale."
  []
  (let [db (store/seed-db)
        actor (op/build db)]
    ;; -- clean path: booking-1 (JPN, patch-test 24h ago, allergy resolved) --
    (exec! actor "console-1" {:op :booking/intake :subject "booking-1"
                              :patch {:id "booking-1" :client-name "Sakura Tanaka"}})
    (exec! actor "console-2" {:op :jurisdiction/assess :subject "booking-1"})
    (approve! actor "console-2")
    (exec! actor "console-3" {:op :allergy/screen :subject "booking-1"})
    (approve! actor "console-3")
    (exec! actor "console-4" {:op :treatment/perform :subject "booking-1"})
    (approve! actor "console-4")

    ;; -- HARD hold: no official spec-basis for booking-2's jurisdiction --
    (exec! actor "console-5" {:op :jurisdiction/assess :subject "booking-2" :no-spec? true})

    ;; -- assess+approve booking-3 first (evidence on file), then HARD hold
    ;;    on its own stale patch-test window --
    (exec! actor "console-6" {:op :jurisdiction/assess :subject "booking-3"})
    (approve! actor "console-6")
    (exec! actor "console-7" {:op :treatment/perform :subject "booking-3"})

    ;; -- HARD hold: booking-4's unresolved allergy flag, screened directly --
    (exec! actor "console-8" {:op :allergy/screen :subject "booking-4"})

    db))

;; ----------------------------- rendering helpers -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")
      (str/replace "\"" "&quot;")))

(defn- kw-str
  "Full keyword text (namespace/name), no leading `:` -- unlike
  `clojure.core/name`, which silently drops the namespace
  (`:booking/intake` -> `\"intake\"`, losing which write op it was)."
  [k]
  (if (keyword? k)
    (if-let [ns (namespace k)] (str ns "/" (name k)) (name k))
    (str k)))

(defn- last-fact-for
  "The most recent ledger fact concerning `subject-id`, keyed on this
  repo's real subject-key field name -- `:subject` (confirmed by
  reading `salon.operation/commit-fact` and `salon.governor/hold-fact`,
  both of which write `:subject (:subject request)`)."
  [ledger subject-id]
  (last (filter #(= subject-id (:subject %)) ledger)))

(defn- status-cell [ledger subject-id]
  (if-let [f (last-fact-for ledger subject-id)]
    (case (:t f)
      :committed
      (str "<span class=\"ok\">committed</span> "
           "<span class=\"muted\">(" (esc (kw-str (:op f))) ")</span>")

      :approval-granted
      (str "<span class=\"ok\">approved &amp; committed</span> "
           "<span class=\"muted\">(" (esc (kw-str (:op f))) ")</span>")

      :governor-hold
      (str "<span class=\"critical\">HARD hold</span> "
           "<code>" (esc (str/join ", " (map kw-str (:basis f)))) "</code>")

      :approval-requested
      (str "<span class=\"warn\">pending approval</span> "
           "<span class=\"muted\">(" (esc (kw-str (:op f))) ")</span>")

      (str "<span class=\"muted\">" (esc (kw-str (:t f))) "</span>"))
    "<span class=\"muted\">no activity</span>"))

(defn- booking-row [ledger b]
  (str "<tr>"
       "<td><code>" (esc (:id b)) "</code></td>"
       "<td>" (esc (:client-name b)) "</td>"
       "<td>" (esc (:proposed-treatment b)) "</td>"
       "<td>" (esc (:jurisdiction b)) "</td>"
       "<td>" (esc (:hours-since-patch-test b)) "h</td>"
       "<td>" (if (:allergy-flag-resolved? b)
                "<span class=\"ok\">resolved</span>"
                "<span class=\"critical\">unresolved</span>") "</td>"
       "<td>" (if (:treatment-completed? b)
                (str "<span class=\"ok\">completed</span> "
                     "<code>" (esc (or (:completion-number b) "")) "</code>")
                "<span class=\"muted\">not yet</span>") "</td>"
       "<td>" (status-cell ledger (:id b)) "</td>"
       "</tr>"))

(defn- committed-row [f]
  (str "<tr>"
       "<td>" (esc (kw-str (:op f))) "</td>"
       "<td><code>" (esc (:subject f)) "</code></td>"
       "<td>" (esc (str/join ", " (map str (:basis f)))) "</td>"
       "<td>" (esc (:summary f)) "</td>"
       "</tr>"))

(defn- ledger-row [i f]
  (str "<tr>"
       "<td>" i "</td>"
       "<td><code>" (esc (kw-str (:t f))) "</code></td>"
       "<td>" (esc (kw-str (:op f))) "</td>"
       "<td><code>" (esc (:subject f)) "</code></td>"
       "<td>" (esc (some-> (:disposition f) name)) "</td>"
       "<td>" (if (seq (:basis f))
                (esc (str/join ", " (map kw-str (:basis f))))
                "<span class=\"muted\">--</span>") "</td>"
       "<td>" (if (:confidence f) (format "%.2f" (double (:confidence f))) "") "</td>"
       "</tr>"))

(def ^:private action-gate-rows
  "Static description of salon's own op contract -- sourced from
  `salon.phase/phases` (phase-3 auto/write eligibility) and
  `salon.governor`'s five HARD checks + one high-stakes gate. Not
  db-derived (this table describes the CONTRACT, not a run outcome)."
  [{:op ":booking/intake"       :phase3 "auto-commit (governor-clean)"
    :hard "(no op-specific HARD check; still subject to allergy-flag-unresolved if on file)"}
   {:op ":jurisdiction/assess"  :phase3 "escalate (:phase-approval, even if governor-clean)"
    :hard "no-spec-basis"}
   {:op ":allergy/screen"       :phase3 "escalate (:phase-approval, even if governor-clean)"
    :hard "allergy-flag-unresolved"}
   {:op ":treatment/perform"    :phase3 "escalate (:actuation, NEVER auto-commits at any phase)"
    :hard "evidence-incomplete, patch-test-window-exceeded, allergy-flag-unresolved, already-completed"}])

(defn- action-gate-row [{:keys [op phase3 hard]}]
  (str "<tr><td><code>" (esc op) "</code></td><td>" (esc phase3) "</td><td>" (esc hard) "</td></tr>"))

(defn render
  "Full operator-console HTML page for `db` (post `run-demo!`)."
  [db]
  (let [bookings (store/all-bookings db)
        ledger (store/ledger db)
        committed (filter #(= :committed (:t %)) ledger)]
    (str/join
     "\n"
     ["<!doctype html>"
      "<html lang=\"ja\"><head><meta charset=\"utf-8\">"
      "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
      "<title>cloud-itonami-isic-9602 &middot; salon.render-html</title>"
      "<style>"
      "table { width: 100%; border-collapse: collapse; font-size: 14px; }"
      ".ok { color: #137a3f; }"
      "body { font-family: system-ui,-apple-system,sans-serif; margin: 0; color: #1a1a1a; background: #fafafa; }"
      "header.bar { display: flex; align-items: center; gap: 12px; padding: 12px 20px; background: #fff; border-bottom: 1px solid #e5e5e5; }"
      "th, td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #f0f0f0; }"
      "h2 { margin-top: 0; font-size: 15px; }"
      ".warn { color: #b25c00; background: #fff8e1; padding: 2px 6px; border-radius: 4px; }"
      "main { max-width: 980px; margin: 24px auto; padding: 0 20px; }"
      "header.bar h1 { font-size: 18px; margin: 0; font-weight: 600; }"
      ".muted { color: #888; font-size: 13px; }"
      ".critical { color: #fff; background: #b3261e; padding: 2px 6px; border-radius: 4px; font-weight: 600; }"
      ".card { background: #fff; border: 1px solid #e5e5e5; border-radius: 8px; padding: 16px; margin-bottom: 16px; }"
      ".err { color: #b3261e; background: #fbe9e7; padding: 2px 6px; border-radius: 4px; }"
      "th { font-weight: 600; color: #555; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }"
      "header.bar .badge { margin-left: auto; font-size: 12px; color: #666; }"
      "code { font-size: 12px; background: #f4f4f4; padding: 1px 4px; border-radius: 3px; }"
      "</style></head><body>"
      "<header class=\"bar\"><h1>cloud-itonami-isic-9602 &middot; salon operator console</h1>"
      "<span class=\"badge\">ISIC 9602 &middot; hairdressing and other beauty treatment &middot; generated by salon.render-html</span>"
      "</header>"
      "<main>"

      "<section class=\"card\"><h2>Bookings (client directory)</h2>"
      "<table><thead><tr><th>id</th><th>client</th><th>proposed treatment</th>"
      "<th>jurisdiction</th><th>hours since patch test</th><th>allergy flag</th>"
      "<th>completion</th><th>latest status</th></tr></thead><tbody>"
      (str/join "\n" (map (partial booking-row ledger) bookings))
      "</tbody></table></section>"

      "<section class=\"card\"><h2>Committed records (this run)</h2>"
      "<table><thead><tr><th>op</th><th>subject</th><th>basis</th><th>summary</th></tr></thead><tbody>"
      (str/join "\n" (map committed-row committed))
      "</tbody></table></section>"

      "<section class=\"card\"><h2>Action gate (salon.governor / salon.phase contract)</h2>"
      "<table><thead><tr><th>op</th><th>phase 3 disposition when governor-clean</th>"
      "<th>HARD hold rules (un-overridable)</th></tr></thead><tbody>"
      (str/join "\n" (map action-gate-row action-gate-rows))
      "</tbody></table></section>"

      "<section class=\"card\"><h2>Audit ledger (this run)</h2>"
      "<table><thead><tr><th>#</th><th>type</th><th>op</th><th>subject</th>"
      "<th>disposition</th><th>basis/rule</th><th>confidence</th></tr></thead><tbody>"
      (str/join "\n" (map-indexed ledger-row ledger))
      "</tbody></table></section>"

      "</main></body></html>"])))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out)))
