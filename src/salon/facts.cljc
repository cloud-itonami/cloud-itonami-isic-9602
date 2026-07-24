(ns salon.facts
  "Per-jurisdiction hairdressing/beauty-treatment regulatory catalog --
  the G2-style spec-basis table the Personal Service Safety Governor
  checks every jurisdiction/assess proposal against ('did the advisor
  cite an OFFICIAL public source for this jurisdiction's practitioner-
  licensing/chemical-safety requirements, or did it invent one?').

  Coverage is reported HONESTLY (see `coverage`), the same discipline
  every sibling actor's `facts` namespace uses: a jurisdiction not in
  this table has NO spec-basis, full stop -- the advisor must not
  fabricate one, and the governor holds if it tries.

  Seed values are drawn from each jurisdiction's official cosmetology-
  licensing/workplace-chemical-safety regulator (see `:provenance`);
  they are a STARTING catalog, not a from-scratch survey of all ~194
  jurisdictions. Extending coverage is additive: add one map to
  `catalog`, cite a real source, done -- never invent a jurisdiction's
  requirements to make coverage look bigger.

  Like `wagering.facts`'s/`parksafety.facts`'s/`veterinary.facts`'s
  federated jurisdictions, the USA entry cites a single representative
  state cosmetology board (practitioner licensing is regulated at
  state level in the US) rather than a state-by-state survey; the GBR
  entry cites the workplace chemical-safety regulator (the UK has no
  mandatory national hairdresser-licensing scheme, but COSHH chemical-
  safety duties apply to every salon) -- an honest representative
  citation, the same simplification every prior catalog makes when a
  jurisdiction's real regulatory structure is itself federated or has
  no single licensing body.

  Ireland (IRL) follows the SAME representative-citation posture as
  GBR: Ireland likewise has no mandatory national hairdresser-
  licensing scheme, but the Health and Safety Authority (HSA)
  enforces the Safety, Health and Welfare at Work (Chemical Agents)
  Regulations 2001 (S.I. No. 619 of 2001, as amended 2001 to 2026) --
  Regulation 4 requires every employer to determine whether hazardous
  chemical agents are present at the workplace and to assess the risk
  to employee safety and health those agents present. The HSA also
  publishes a dedicated sector-specific guidance document, 'Chemical
  Safety in Hairdressing' (Information Sheet, March 2019), which walks
  through the exact dermatitis/allergy risks (e.g. paraphenylenediamine
  in permanent hair dyes, persulphates in bleaches) this catalog's own
  `:required-evidence` set (patch-test/allergy-alert-test record,
  product safety-data-sheet reference) is designed to catch.")

(def catalog
  "iso3 -> requirement map. `:required-evidence` mirrors the generic
  client-consent/patch-test/practitioner-license/safety-data-sheet
  evidence set submitted in some form; `:legal-basis` / `:owner-
  authority` / `:provenance` are the G2 citation the governor requires
  before any :jurisdiction/assess proposal can commit."
  {"JPN" {:name "Japan"
          :owner-authority "厚生労働省 (Ministry of Health, Labour and Welfare)"
          :legal-basis "美容師法 (Beautician Act)"
          :national-spec "美容師免許・衛生管理基準"
          :provenance "https://www.mhlw.go.jp/"
          :required-evidence ["顧客同意/既往歴記録 (client consent/health-history record)"
                              "パッチテスト/アレルギーテスト記録 (patch-test/allergy-alert-test record)"
                              "美容師免許確認記録 (practitioner license verification)"
                              "製品安全データシート参照 (product safety-data-sheet reference)"]}
   "USA" {:name "United States"
          :owner-authority "California Board of Barbering and Cosmetology"
          :legal-basis "California Business and Professions Code, Division 3, Chapter 10 (Barbering and Cosmetology Act)"
          :national-spec "Cosmetology licensing and salon sanitation requirements"
          :provenance "https://www.barbercosmo.ca.gov/"
          :required-evidence ["Client consent/health-history record"
                              "Patch-test/allergy-alert-test record"
                              "Practitioner license verification"
                              "Product safety-data-sheet reference"]}
   "GBR" {:name "United Kingdom"
          :owner-authority "Health and Safety Executive (HSE)"
          :legal-basis "Control of Substances Hazardous to Health (COSHH) Regulations 2002"
          :national-spec "COSHH chemical-safety duties for salon treatments"
          :provenance "https://www.hse.gov.uk/coshh/"
          :required-evidence ["Client consent/health-history record"
                              "Patch-test/allergy-alert-test record"
                              "Practitioner license verification"
                              "Product safety-data-sheet reference"]}
   "DEU" {:name "Germany"
          :owner-authority "Handwerkskammern (Chambers of Skilled Crafts)"
          :legal-basis "Handwerksordnung (HwO), Anlage A (Friseurhandwerk)"
          :national-spec "Meisterpflicht und Hygienevorschriften im Friseurhandwerk"
          :provenance "https://www.zdh.de/"
          :required-evidence ["Kundeneinwilligung/Anamnese (client consent/health-history record)"
                              "Allergietest-/Verträglichkeitstestnachweis (patch-test/allergy-alert-test record)"
                              "Meisterbrief-/Zulassungsnachweis (practitioner license verification)"
                              "Sicherheitsdatenblatt-Referenz (product safety-data-sheet reference)"]}
   "IRL" {:name "Ireland"
          :owner-authority "Health and Safety Authority (HSA)"
          :legal-basis "Safety, Health and Welfare at Work (Chemical Agents) Regulations 2001 (S.I. No. 619 of 2001, as amended 2001 to 2026), Regulation 4"
          :national-spec "Regulation 4(1): 'it shall be the duty of every employer to determine whether any hazardous chemical agents are present at the workplace and to assess any risk to the safety and health of employees arising from the presence of those chemical agents'; HSA 'Chemical Safety in Hairdressing' Information Sheet (March 2019)"
          :provenance "https://www.irishstatutebook.ie/eli/2001/si/619/made/en/print"
          :required-evidence ["Client consent/health-history record"
                              "Patch-test/allergy-alert-test record"
                              "Practitioner license verification"
                              "Product safety-data-sheet reference"]}})

(defn spec-basis
  "The jurisdiction's requirement map, or nil -- nil means NO spec-basis,
  and the governor must hold any proposal that tries to perform a
  treatment on it."
  [iso3]
  (get catalog iso3))

(defn coverage
  "Honest coverage report: how many of the requested jurisdictions actually
  have a spec-basis entry. Never report a missing jurisdiction as covered."
  ([] (coverage (keys catalog)))
  ([iso3s]
   (let [have (filter catalog iso3s)
         missing (remove catalog iso3s)]
     {:requested (count iso3s)
      :covered (count have)
      :covered-jurisdictions (vec (sort have))
      :missing-jurisdictions (vec (sort missing))
      :note (str "cloud-itonami-isic-9602 R0: " (count catalog)
                 " jurisdictions seeded with an official spec-basis. "
                 "This is a starting catalog, not a survey of all ~194 "
                 "jurisdictions -- extend `salon.facts/catalog`, "
                 "never fabricate a jurisdiction's requirements.")})))

(defn required-evidence-satisfied?
  "Does `submitted` (a set/coll of evidence keywords or strings) satisfy
  every evidence item listed for `iso3`? Missing spec-basis -> never
  satisfied."
  [iso3 submitted]
  (when-let [{:keys [required-evidence]} (spec-basis iso3)]
    (let [need (count required-evidence)
          have (count (filter (set submitted) required-evidence))]
      (= need have))))

(defn evidence-checklist [iso3]
  (:required-evidence (spec-basis iso3) []))
