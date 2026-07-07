(ns salon.salonopsllm
  "SalonOps-LLM client -- the *contained intelligence node* for the
  hairdressing/beauty-treatment actor.

  It normalizes client-booking intake, drafts a per-jurisdiction
  practitioner-licensing/chemical-safety evidence checklist, screens
  bookings for an unresolved allergy flag, and drafts the treatment-
  performance action. CRITICAL: it is a smart-but-untrusted advisor.
  It returns a *proposal* (with a rationale + the fields it cited),
  never a committed record or a real treatment. Every output is
  censored downstream by `salon.governor` before anything touches the
  SSoT, and `:treatment/perform` proposals NEVER auto-commit at any
  phase -- see README `Actuation`.

  Like every sibling actor's advisor, this is a deterministic mock so
  the actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the spec-basis gate
     :cites      [kw|str ..]    ; facts/sources the LLM used -- SCANNED too
     :effect     kw             ; how a commit would mutate the SSoT
     :stake      kw|nil         ; :actuation/perform-treatment | nil
     :confidence 0..1}"
  (:require #?(:clj  [clojure.edn :as edn]
               :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [salon.facts :as facts]
            [salon.registry :as registry]
            [salon.store :as store]
            [langchain.model :as model]))

(defn- normalize-intake
  "Directory upsert -- the LLM only normalizes/validates the patch; it
  does not invent the client, patch-test figures or jurisdiction. High
  confidence, low stakes."
  [_db {:keys [patch]}]
  {:summary    (str "予約記録更新: " (pr-str (keys patch)))
   :rationale  "入力 patch の正規化のみ。新規事実の生成なし。"
   :cites      (vec (keys patch))
   :effect     :booking/upsert
   :value      patch
   :stake      nil
   :confidence 0.97})

(defn- assess-jurisdiction
  "Per-jurisdiction practitioner-licensing/chemical-safety evidence
  checklist draft. `:no-spec?` injects the failure mode we must defend
  against: proposing a checklist for a jurisdiction with NO official
  spec-basis in `salon.facts` -- the Personal Service Safety Governor
  must reject this (never invent a jurisdiction's requirements)."
  [db {:keys [subject no-spec?]}]
  (let [b (store/booking db subject)
        iso3 (if no-spec? "ATL" (:jurisdiction b))
        sb (facts/spec-basis iso3)]
    (if (nil? sb)
      {:summary    (str iso3 " の公式spec-basisが見つかりません")
       :rationale  "salon.facts に未登録の法域。要件を推測で作らない。"
       :cites      []
       :effect     :assessment/set
       :value      {:jurisdiction iso3 :checklist [] :spec-basis nil}
       :stake      nil
       :confidence 0.9}
      {:summary    (str iso3 " (" (:owner-authority sb) ") 向け必要書類 "
                        (count (:required-evidence sb)) " 件を提案")
       :rationale  (str "公式ソース: " (:provenance sb) " / 法的根拠: " (:legal-basis sb))
       :cites      [(:legal-basis sb) (:provenance sb)]
       :effect     :assessment/set
       :value      {:jurisdiction iso3
                    :checklist (:required-evidence sb)
                    :spec-basis (:provenance sb)
                    :legal-basis (:legal-basis sb)}
       :stake      nil
       :confidence 0.9})))

(defn- screen-allergy
  "Allergy-flag screening draft. `:allergy-flag-resolved?` on the
  booking record injects the failure mode: the Personal Service Safety
  Governor must HOLD, un-overridably, on any unresolved allergy flag."
  [db {:keys [subject]}]
  (let [b (store/booking db subject)]
    (cond
      (nil? b)
      {:summary "対象予約が見つかりません" :rationale "no booking record"
       :cites [] :effect :allergy-screening/set :value {:booking-id subject :verdict :unknown}
       :stake nil :confidence 0.0}

      (false? (:allergy-flag-resolved? b))
      {:summary    (str (:client-name b) ": 未解決のアレルギーフラグを検出")
       :rationale  "スクリーニングが未解決のアレルギーフラグを検出。人手確認とホールドが必須。"
       :cites      [:allergy-check]
       :effect     :allergy-screening/set
       :value      {:booking-id subject :verdict :unresolved}
       :stake      nil
       :confidence 0.95}

      :else
      {:summary    (str (:client-name b) ": アレルギーフラグ解決済み")
       :rationale  "アレルギーフラグスクリーニング完了。"
       :cites      [:allergy-check]
       :effect     :allergy-screening/set
       :value      {:booking-id subject :verdict :resolved}
       :stake      nil
       :confidence 0.9})))

(defn- propose-treatment-performance
  "Draft the actual TREATMENT-PERFORMANCE action -- performing a real
  chemical or skin-piercing treatment. ALWAYS `:stake :actuation/
  perform-treatment` -- this is a REAL-WORLD act (a real chemical is
  applied to or a real piercing is made in a client's body), never a
  draft the actor may auto-run. See README `Actuation`: no phase ever
  adds this op to a phase's `:auto` set (`salon.phase`); the governor
  also always escalates on `:actuation/perform-treatment`. Two
  independent layers agree, deliberately."
  [db {:keys [subject]}]
  (let [b (store/booking db subject)
        stale? (and b (registry/patch-test-window-exceeded? b))]
    {:summary    (str subject " 向け施術実施提案"
                      (when b (str " (client=" (:client-name b) ")")))
     :rationale  (if b
                   (str "hours-since-patch-test=" (:hours-since-patch-test b)
                        " max-patch-test-window-hours=" registry/max-patch-test-window-hours)
                   "予約が見つかりません")
     :cites      (if b [subject] [])
     :effect     :booking/mark-completed
     :value      {:booking-id subject}
     :stake      :actuation/perform-treatment
     :confidence (if stale? 0.3 0.9)}))

(defn infer
  "Route a request to the right proposal generator.
  request: {:op kw :subject id ...op-specific...}"
  [db {:keys [op] :as request}]
  (case op
    :booking/intake         (normalize-intake db request)
    :jurisdiction/assess        (assess-jurisdiction db request)
    :allergy/screen                 (screen-allergy db request)
    :treatment/perform                   (propose-treatment-performance db request)
    {:summary "未対応の操作" :rationale (str op) :cites []
     :effect :noop :stake nil :confidence 0.0}))

;; ----------------------------- Advisor protocol -----------------------------

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

(defn mock-advisor
  "The deterministic advisor (the `infer` logic above). Default everywhere."
  [] (reify Advisor (-advise [_ st req] (infer st req))))

(def ^:private system-prompt
  (str "あなたは美容サロンの施術実施エージェントの助言者です。"
       "与えられた事実のみに基づき、提案を1つだけEDNマップで返します。説明や前置きは"
       "一切書かず、EDNだけを出力します。\n"
       "キー: :summary(人向けドラフト) :rationale(根拠/必ず事実から) "
       ":cites(使った事実キーのベクタ) "
       ":effect(:booking/upsert|:assessment/set|:allergy-screening/set|"
       ":booking/mark-completed) "
       ":stake(:actuation/perform-treatment か nil) :confidence(0..1)。\n"
       "重要: 登録されていない法域の要件を絶対に創作してはいけません。"
       "spec-basisが無い場合は :cites を空にし confidence を上げないこと。"))

(defn- facts-for [st {:keys [op subject]}]
  (case op
    :jurisdiction/assess  {:booking (store/booking st subject)}
    :allergy/screen       {:booking (store/booking st subject)}
    :treatment/perform    {:booking (store/booking st subject)}
    {:booking (store/booking st subject)}))

(defn- parse-proposal
  "Parse the model's EDN proposal defensively. Any parse/shape failure
  yields a safe low-confidence noop so the Personal Service Safety
  Governor escalates/holds -- an LLM hiccup can never auto-perform a
  treatment."
  [content]
  (let [p (try (edn/read-string (str/trim (str content)))
               (catch #?(:clj Exception :cljs :default) _ nil))]
    (if (map? p)
      (-> p
          (update :cites #(vec (or % [])))
          (update :confidence #(if (number? %) (double %) 0.0))
          (update :effect #(or % :noop)))
      {:summary "LLM応答を解釈できませんでした" :rationale (str content)
       :cites [] :effect :noop :stake nil :confidence 0.0})))

(defn llm-advisor
  "An advisor backed by a `langchain.model/ChatModel` (real inference)."
  ([chat-model] (llm-advisor chat-model {}))
  ([chat-model gen-opts]
   (reify Advisor
     (-advise [_ st req]
       (let [msgs [{:role :system :content system-prompt}
                   {:role :user :content (str "操作: " (:op req)
                                              "\n対象: " (:subject req)
                                              "\n事実: " (pr-str (facts-for st req)))}]
             resp (model/-generate chat-model msgs gen-opts)]
         (parse-proposal (:content resp)))))))

(defn trace
  "Decision-grounded audit record -- persisted to the :audit channel."
  [request proposal]
  {:t          :salonopsllm-proposal
   :op         (:op request)
   :subject    (:subject request)
   :summary    (:summary proposal)
   :rationale  (:rationale proposal)
   :cites      (:cites proposal)
   :confidence (:confidence proposal)})
