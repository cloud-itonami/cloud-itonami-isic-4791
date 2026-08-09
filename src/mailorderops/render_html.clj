(ns mailorderops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave2 flagship item2 / REF isic-9522): this repo previously had NO demo
  page and no generator at all. This namespace drives the REAL actor stack
  (`mailorderops.operation` -> `mailorderops.governor` ->
  `mailorderops.store`) through a scenario adapted from this repo's own
  `mailorderops.sim` demo driver (`clojure -M:run`, confirmed BEFORE
  writing this file to produce a sensible ledger against the real seeded
  seller/vendor ids `seller-1`..`seller-4` / `vendor-1`..`vendor-2` --
  unlike `cloud-itonami-isic-851`'s `schoolops.sim`, this repo's own sim
  driver uses ids that DO match `mailorderops.store/demo-data`, so it was
  safe to reuse rather than author from scratch), trimmed to a
  representative subset (phase-3 auto-commits, always-escalate
  approve-paths, and multiple DISTINCT HARD-hold reasons) and rendered
  deterministically -- no invented numbers, no timestamps in the page
  content, byte-identical across reruns against the same seed (verify
  by diffing two consecutive runs).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [mailorderops.store :as store]
            [mailorderops.operation :as op]
            [langgraph.graph :as g]))

(def ^:private operator
  {:actor-id "op-1" :actor-role :mail-order-ops-coordinator :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach: seller-1 clears a clean phase-3 order-record log
  (auto-commit), a fulfillment-operation schedule (auto-commit), a
  low-cost supply-order against verified vendor-1 (auto-commit), a
  high-cost supply-order (ALWAYS escalates on cost threshold -- approved),
  and a fraud-concern flag (ALWAYS escalates -- approved); seller-99
  HARD-holds on an unregistered merchant account; seller-3 HARD-holds as
  registered-but-unverified; seller-4 HARD-holds as registered+verified
  but NOT payment-processor-linked (the e-commerce-specific gate); and a
  supply-order naming vendor-2 HARD-holds on `:vendor-unverified`. Every
  HARD hold never reaches a human. Returns the resulting store -- every
  field read by `render` below is real governor/store output, not a
  hand-typed copy."
  []
  (let [db (store/seed-db)
        actor (op/build db)]
    ;; seller-1 happy path (phase 3 auto-commit)
    (exec! actor "s1-log" {:op :log-order-record :seller-id "seller-1"
                           :patch {:orders-placed 30 :returns 1 :shipments-dispatched 29}})
    (exec! actor "s1-fulfill" {:op :schedule-fulfillment-operation :seller-id "seller-1"
                               :patch {:window "pick-pack-ship" :date "2026-07-20" :dock "dock-4"}})
    (exec! actor "s1-supply-low" {:op :coordinate-supply-order :seller-id "seller-1"
                                  :patch {:item "inventory restock" :quantity 200
                                          :estimated-cost 480.0 :vendor-id "vendor-1"}})

    ;; always-escalate ops (human approves)
    (exec! actor "s1-supply-high" {:op :coordinate-supply-order :seller-id "seller-1"
                                   :patch {:item "seasonal inventory build" :quantity 20
                                           :estimated-cost 3800.0 :vendor-id "vendor-1"}})
    (approve! actor "s1-supply-high")

    (exec! actor "s1-fraud" {:op :flag-fraud-concern :seller-id "seller-1"
                             :patch {:concern "unusual order velocity from a single new shipping address, chargeback notice received on a prior order"
                                     :confidence 0.92}})
    (approve! actor "s1-fraud")

    ;; HARD holds -- never reach a human
    (exec! actor "s99-log" {:op :log-order-record :seller-id "seller-99"
                            :patch {:orders-placed 0}})
    (exec! actor "s3-log" {:op :log-order-record :seller-id "seller-3"
                           :patch {:orders-placed 10}})
    (exec! actor "s4-log" {:op :log-order-record :seller-id "seller-4"
                           :patch {:orders-placed 10}})
    (exec! actor "s1-vendor2" {:op :coordinate-supply-order :seller-id "seller-1"
                               :patch {:item "import general merchandise" :quantity 50
                                       :estimated-cost 300.0 :vendor-id "vendor-2"}})
    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger seller-id]
  (last (filter #(= (:seller-id %) seller-id) ledger)))

(defn- status-cell [ledger seller-id]
  (let [f (last-fact-for ledger seller-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :governor-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- yn [b]
  (if b "<span class=\"ok\">yes</span>" "<span class=\"critical\">no</span>"))

(defn- seller-row [ledger {:keys [seller-id name registered? verified? payment-processor-linked?]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc seller-id) (esc name)
          (yn registered?) (yn verified?) (yn payment-processor-linked?)
          (status-cell ledger seller-id)))

(defn- vendor-row [{:keys [vendor-id name registered? verified?]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc vendor-id) (esc name) (yn registered?) (yn verified?)))

(defn- ledger-row [{:keys [t op seller-id disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc seller-id)
          (esc (or (some->> basis (map #(if (keyword? %) (name %) (str %))) (str/join ", "))
                    (some-> disposition name) ""))))

(defn- coord-row [{:keys [op seller-id value]}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name (or op :n-a))) (esc seller-id)
          (esc (pr-str (or value {})))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own closed op contract
  ;; (README Ops, mailorderops.governor / mailorderops.phase) --
  ;; documentation of fixed behavior, not runtime telemetry.
  ["        <tr><td><code>:log-order-record</code></td><td><span class=\"ok\">phase-3 auto-commit when clean &middot; seller must be registered+verified+payment-processor-linked</span></td></tr>"
   "        <tr><td><code>:schedule-fulfillment-operation</code></td><td><span class=\"ok\">phase-3 auto-commit when clean &middot; warehouse pick/pack/ship coordination only</span></td></tr>"
   "        <tr><td><code>:coordinate-supply-order</code></td><td><span class=\"warn\">phase-3 auto when clean &amp; low cost; ALWAYS escalate when estimated-cost &gt; threshold; HARD hold if vendor unverified</span></td></tr>"
   "        <tr><td><code>:flag-fraud-concern</code></td><td><span class=\"warn\">ALWAYS human approval &middot; observation only &middot; never finalizes fraud/chargeback/payment-dispute</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        sellers (store/all-seller-records db)
        vendors (store/all-vendor-records db)
        coords (vec (store/coordination-log db))
        seller-rows (str/join "\n" (map (partial seller-row ledger) sellers))
        vendor-rows (str/join "\n" (map vendor-row vendors))
        ledger-rows (str/join "\n" (map ledger-row ledger))
        coord-rows (str/join "\n" (map coord-row coords))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-4791 &middot; mail-order / internet retail</title><style>"
     (jp-go-dds.skin/dds+skin)
     "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Retail sale via mail order houses or via Internet (ISIC 4791) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · fraud/chargeback finalization permanently excluded</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Merchant accounts (sellers)</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>mailorderops.store</code> via <code>mailorderops.render-html</code> (<code>clojure -M:dev:render-html</code>). Primary gate is seller/merchant-account verification (registered + verified + payment-processor-linked), not a physical storefront.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Seller</th><th>Name</th><th>Registered</th><th>Verified</th><th>Payment-processor linked</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     seller-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Inventory vendors</h2>\n"
     "    <p class=\"muted\">Supply-order counterparty directory — <code>:coordinate-supply-order</code> independently re-checks <code>:registered?</code>/<code>:verified?</code> and never trusts proposal self-report.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Vendor</th><th>Name</th><th>Registered</th><th>Verified</th></tr></thead>\n"
     "      <tbody>\n"
     vendor-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (Mail-Order Retail Governor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden. This actor coordinates only — finalizing a fraud determination, chargeback ruling, or payment-dispute resolution is a permanent scope exclusion.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Committed coordination log (this run)</h2>\n"
     "    <p class=\"muted\">Records that actually cleared the governor and phase gate (auto-commit or human-approved escalate).</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Seller</th><th>Value</th></tr></thead>\n"
     "      <tbody>\n"
     coord-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Seller</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)
        hard-holds (count (filter #(= :governor-hold (:t %)) (store/ledger db)))]
    (-> out java.io.File. .getParentFile (some-> .mkdirs))
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/coordination-log db)) "coordination commits,"
             hard-holds "HARD holds )")
    (when (< hard-holds 1)
      (binding [*out* *err*]
        (println "ERROR: expected ≥1 HARD hold in demo ledger, got" hard-holds))
      (System/exit 1))))
