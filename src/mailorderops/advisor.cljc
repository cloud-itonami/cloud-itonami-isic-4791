(ns mailorderops.advisor
  "MailOrderRetailAdvisor -- the *contained intelligence node* for the
  ISIC-4791 'Retail sale via mail order houses or via Internet'
  (mail-order / e-commerce retail -- NO physical storefront)
  operations-coordination actor.

  It drafts exactly four kinds of back-office proposal from a closed
  allowlist: order/shipment/return transaction logging, warehouse
  fulfillment (pick/pack/ship) scheduling, merchandise/inventory
  supply-order coordination, and suspected-fraud/chargeback/
  payment-dispute-concern flagging. CRITICAL: it is a smart-but-untrusted
  advisor. It returns a *proposal* (with a rationale + the fields it
  cited), never a committed record and NEVER a direct actuation -- every
  proposal's `:effect` is always `:propose`. Every output is censored
  downstream by `mailorderops.governor` before anything touches the SSoT.

  This advisor NEVER drafts a direct finalization of a fraud
  determination, a chargeback ruling, or a payment-dispute resolution --
  those are permanently out of scope for this actor, not merely
  un-implemented. `mailorderops.governor`'s `scope-exclusion-violations`
  independently re-scans every proposal for exactly this failure mode (a
  compromised or confused advisor drifting into scope it must never
  touch) and HARD-holds it, regardless of confidence or op.

  Like every sibling actor's advisor, this is a deterministic mock so the
  actor graph runs offline and the governor contract is exercised
  end-to-end. In production this calls a real LLM (kotoba-llm or
  equivalent) with the same proposal shape.

  Proposal shape (all kinds):
    {:op         kw             ; echoes the request op
     :seller-id  str
     :summary    str            ; human-facing draft / finding
     :rationale  str            ; why -- SCANNED by the scope-exclusion gate
     :cites      [str ..]       ; facts/sources the advisor used -- SCANNED too
     :effect     :propose       ; ALWAYS :propose -- never a direct actuation
     :value      map            ; the draft payload a human/system would review
     :confidence 0..1}")

(defprotocol Advisor
  (-advise [advisor store request] "store + request -> proposal map"))

;; ----------------------------- proposal generators -----------------------------

(defn- propose-order-record
  "Draft an order/shipment/return transaction log entry. Pure logging of
  observed transactions (orders placed, shipments dispatched, returns
  processed) -- never a fraud/chargeback finalization."
  [_db {:keys [seller-id patch]}]
  {:op         :log-order-record
   :seller-id  seller-id
   :summary    (str seller-id " の注文/出荷/返品記録を記録: " (pr-str (keys patch)))
   :rationale  "注文・出荷・返品処理の観察記録のみ。不正判定やチャージバック裁定は含まない。"
   :cites      [seller-id]
   :effect     :propose
   :value      (merge {:seller-id seller-id} patch)
   :confidence 0.93})

(defn- propose-fulfillment-operation
  "Draft a warehouse fulfillment (pick/pack/ship) scheduling proposal (a
  logistics/dispatch-window entry, never a direct actuation)."
  [_db {:keys [seller-id patch]}]
  {:op         :schedule-fulfillment-operation
   :seller-id  seller-id
   :summary    (str seller-id " の倉庫ピック/パック/出荷予定を提案: " (pr-str (keys patch)))
   :rationale  "倉庫のピッキング/梱包/出荷スケジュール調整提案のみ。最終的な出荷実行は人間/既存WMSが確定する。"
   :cites      [seller-id]
   :effect     :propose
   :value      (merge {:seller-id seller-id} patch)
   :confidence 0.88})

(defn- propose-supply-order
  "Draft an inventory procurement coordination request naming a
  registered vendor -- never a finalized purchase order; a human always
  confirms procurement."
  [_db {:keys [seller-id patch]}]
  {:op         :coordinate-supply-order
   :seller-id  seller-id
   :summary    (str seller-id " 向け在庫補充の発注調整を提案: " (pr-str (keys patch)))
   :rationale  "在庫補充のための仕入先発注調整提案のみ。確定発注は人間が行う。"
   :cites      [seller-id]
   :effect     :propose
   :value      (merge {:seller-id seller-id} patch)
   :confidence 0.90})

(defn- propose-fraud-concern
  "Surface an observed suspected-fraud/chargeback/payment-dispute concern
  (stolen-card pattern, unusual order velocity, chargeback notice
  received, payment dispute filed) for HUMAN triage. This op ALWAYS
  escalates in `mailorderops.governor` -- never auto-committed at any
  phase -- regardless of how confident the advisor is that the concern
  is real. Deliberately reports the OBSERVATION only, never a
  finalization/ruling/resolution action, so the default rationale never
  trips the governor's `scope-excluded-terms` (see that var's
  docstring)."
  [_db {:keys [seller-id patch]}]
  {:op         :flag-fraud-concern
   :seller-id  seller-id
   :summary    (str seller-id " の不正利用/チャージバック懸念フラグ: " (pr-str (:concern patch "unknown")))
   :rationale  "不正利用の疑い・チャージバック通知・支払い紛争の観察事実の報告のみ。最終的な不正判定・チャージバック裁定・支払い紛争の解決は行わない。常に人間の確認・対応が必要。"
   :cites      [seller-id]
   :effect     :propose
   :value      (merge {:seller-id seller-id} patch)
   :confidence (or (:confidence patch) 0.85)})

;; ----------------------------- default mock advisor -----------------------------

(defn infer
  "Mock advisor: routes to the correct proposal generator."
  [_db {:keys [op out-of-scope?] :as request}]
  (let [proposal (case op
                   :log-order-record (propose-order-record _db request)
                   :schedule-fulfillment-operation (propose-fulfillment-operation _db request)
                   :coordinate-supply-order (propose-supply-order _db request)
                   :flag-fraud-concern (propose-fraud-concern _db request)
                   {})]
    ;; Test hook: allow injecting scope-excluded content to exercise the
    ;; governor's scope-exclusion block end-to-end. Must be cleared before
    ;; production use.
    (if out-of-scope?
      (update proposal :rationale str " -- actually finalized the chargeback ruling and closed the payment dispute in the merchant's favor")
      proposal)))

(defn trace
  "Audit fact for a proposal generated by this advisor."
  [_request proposal]
  {:t       :advisor-proposal
   :op      (:op proposal)
   :seller-id (:seller-id proposal)
   :summary (:summary proposal)
   :confidence (:confidence proposal)})

(defn mock-advisor
  "The deterministic default advisor for offline demo/test."
  []
  (reify Advisor
    (-advise [_ _store request]
      (infer nil request))))
