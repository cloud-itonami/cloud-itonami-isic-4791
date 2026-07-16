(ns mailorderops.governor
  "MailOrderRetailGovernor -- the independent compliance layer that earns
  the MailOrderRetailAdvisor the right to commit. The advisor has no
  notion of whether a seller is actually a registered, verified,
  payment-processor-linked e-commerce merchant account, whether a named
  inventory supply-order vendor is itself a registered/verified
  counterparty, whether its own proposed `:effect` secretly claims a
  direct actuation instead of a mere proposal, or whether it has silently
  drifted into a permanently out-of-scope decision area (finalizing a
  fraud determination, a chargeback ruling, or a payment-dispute
  resolution), so this MUST be a separate system able to *reject* a
  proposal and fall back to HOLD.

  Unlike a physical-retail sibling actor (e.g. ISIC 4719), this actor has
  NO physical storefront -- its primary gate is a SELLER/MERCHANT-ACCOUNT
  verification check, not a store-verification check. It also carries a
  payment-fraud/chargeback dimension absent from physical retail: the
  closed op allowlist NEVER includes any op that directly finalizes a
  fraud determination, chargeback ruling, or payment-dispute resolution
  -- that territory is a HARD, permanent block, never auto-commit
  eligible, and `:flag-fraud-concern` (the only op that may touch it at
  all, as an OBSERVATION) always escalates to a human.

  This actor's scope is deliberately narrow -- COORDINATION ONLY
  (order/shipment/return transaction logging, warehouse fulfillment
  scheduling, merchandise/inventory supply-order coordination,
  suspected-fraud/chargeback/payment-dispute-concern flagging). It NEVER
  performs or authorizes:
    - directly finalizing a fraud determination (ruling an order/account
      fraudulent)
    - directly finalizing a chargeback ruling (approving/denying/
      reversing a chargeback)
    - directly finalizing a payment-dispute resolution (closing a dispute
      in either party's favor)

  Four HARD checks, ALL permanent, un-overridable by any human approval:

    1. Seller/merchant-account unverified -- the target seller record
                                     must exist AND be independently
                                     confirmed `:registered?`,
                                     `:verified?` AND
                                     `:payment-processor-linked?` in the
                                     store before ANY proposal for it may
                                     commit or even escalate. Never trusts
                                     a proposal's own claim about the
                                     seller -- re-derived from the
                                     seller's own record, the same
                                     'ground truth, not self-report'
                                     discipline every sibling actor's
                                     governor uses. A seller that is
                                     registered and verified but NOT yet
                                     linked to a payment processor (e.g.
                                     mid-onboarding) is treated exactly
                                     like an unverified seller -- this is
                                     the e-commerce-specific adaptation of
                                     the physical-retail 'store
                                     verification' gate.
    2. Vendor unverified          -- for `:coordinate-supply-order` ONLY,
                                     the proposal's own drafted `:value`
                                     must name a `:vendor-id` that
                                     resolves to an independently
                                     `:registered?`/`:verified?` inventory
                                     vendor record. A missing vendor-id,
                                     or one that resolves to an
                                     unregistered or unverified vendor, is
                                     a HARD block.
    3. Effect not :propose        -- every proposal's `:effect` MUST be
                                     `:propose`. Any other effect value
                                     is, by construction, a claim to
                                     directly actuate/commit outside
                                     governance -- HARD block, not merely
                                     low-confidence.
    4. Scope exclusion            -- ANY proposal (regardless of op)
                                     whose op, summary, rationale, cites
                                     or draft value touches directly
                                     finalizing a fraud determination, a
                                     chargeback ruling, or a
                                     payment-dispute resolution is a HARD,
                                     PERMANENT block -- this actor's
                                     charter excludes that territory
                                     structurally, not as a rollout
                                     milestone. Evaluated UNCONDITIONALLY
                                     on every proposal. An op outside the
                                     closed four-op allowlist is the SAME
                                     failure mode (an advisor proposing
                                     something it was never authorized to
                                     propose) and is folded into this same
                                     check. `:flag-fraud-concern` itself
                                     is never excluded by this check --
                                     surfacing a suspected-fraud/
                                     chargeback/payment-dispute concern
                                     for a human is exactly this actor's
                                     job; only FINALIZING/ruling-on/
                                     resolving that concern is excluded
                                     (see `scope-excluded-terms` below --
                                     phrased as the finalization/
                                     resolution ACTION, never a bare noun
                                     like 'fraud' or 'chargeback', so the
                                     default mock advisor's own
                                     `:flag-fraud-concern` rationale never
                                     self-trips this check).

  Two ESCALATE (SOFT) gates, either forces human sign-off:
    - LLM confidence below the floor.
    - The op is `:flag-fraud-concern` -- ALWAYS escalates to a human,
      regardless of confidence, regardless of how clean the proposal
      otherwise is. `mailorderops.phase` independently agrees:
      `:flag-fraud-concern` is never a member of any phase's `:auto` set
      either -- two layers, not one. This op may NEVER become
      auto-commit-eligible -- it is the surfacing step for a
      fraud/chargeback/payment-dispute concern, never the resolution of
      one.
    - A `:coordinate-supply-order` whose drafted `:value` names an
      `:estimated-cost` above `supply-cost-threshold` -- a large-value
      inventory procurement proposal always needs a human sign-off, even
      when the governor and phase would otherwise allow auto-commit."
  (:require [clojure.string :as str]
            [mailorderops.store :as store]))

(def confidence-floor 0.6)

(def supply-cost-threshold
  "Example single-seller inventory-procurement threshold (USD-equivalent
  units, domain-illustrative -- not a universal cross-domain constant).
  A `:coordinate-supply-order` proposal citing an `:estimated-cost` above
  this value ALWAYS escalates to human sign-off, regardless of
  confidence or rollout phase."
  1500.0)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a scope
  violation by construction (see `scope-exclusion-violations`). CRITICAL:
  no op that directly finalizes a fraud determination, a chargeback
  ruling, or a payment-dispute resolution is EVER a member of this set --
  such an op would be, by construction, a permanent scope violation, not
  merely un-implemented."
  #{:log-order-record :schedule-fulfillment-operation
    :coordinate-supply-order :flag-fraud-concern})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not. `:flag-fraud-
  concern` can never be promoted to auto-commit-eligible at any phase --
  see `mailorderops.phase`."
  #{:flag-fraud-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as touching a
  permanently out-of-scope decision area -- directly finalizing a fraud
  determination, a chargeback ruling, or a payment-dispute resolution.
  Scanned across the proposal's op/summary/rationale/cites/value, never
  trusting the advisor's own framing of its intent.

  CRITICAL: every term here is phrased as the finalization/resolution
  ACTION (e.g. 'finalized the chargeback ruling', 'resolved the payment
  dispute'), never a bare noun like 'fraud', 'chargeback' or 'payment
  dispute' -- a bare noun would accidentally match inside this actor's
  own legitimate `:flag-fraud-concern` default proposal text (whose whole
  job is to talk about suspected fraud/chargeback/payment-dispute
  concerns, and whose own printed `:op` keyword literally contains the
  substring 'fraud') and self-block the happy path. See
  `mailorderops.governor-test/default-mock-advisor-proposals-never-self-trip-scope-exclusion`
  for the regression test."
  ["finalize the fraud determination" "finalized the fraud determination" "finalizes the fraud determination"
   "finalize the chargeback ruling" "finalized the chargeback ruling" "finalizes the chargeback ruling"
   "issue the chargeback ruling" "issued the chargeback ruling" "issuing the chargeback ruling"
   "issue a final fraud ruling" "issued a final fraud ruling" "issuing a final fraud ruling"
   "rule the transaction fraudulent" "ruled the transaction fraudulent" "ruling the transaction fraudulent"
   "rule the order fraudulent" "ruled the order fraudulent" "ruling the order fraudulent"
   "determine the order to be fraudulent" "determined the order to be fraudulent" "determining the order to be fraudulent"
   "determine the account to be fraudulent" "determined the account to be fraudulent"
   "resolve the payment dispute" "resolved the payment dispute" "resolving the payment dispute"
   "close the payment dispute in the merchant's favor" "closed the payment dispute in the merchant's favor"
   "close the payment dispute in the customer's favor" "closed the payment dispute in the customer's favor"
   "approve the chargeback" "approved the chargeback" "approving the chargeback"
   "deny the chargeback" "denied the chargeback" "denying the chargeback"
   "reverse the charge" "reversed the charge" "reversing the charge"
   "process the refund reversal" "processed the refund reversal" "processing the refund reversal"
   "不正と断定した" "不正と断定する" "不正利用と確定した" "不正利用として確定した"
   "チャージバックを確定させた" "チャージバック裁定を下した" "チャージバックを承認した" "チャージバックを却下した"
   "支払い紛争を解決した" "支払い紛争の最終判断を下した" "支払い紛争の解決を確定した"
   "返金の取消処理を実行した" "取消処理を実行した"])

;; ----------------------------- checks -----------------------------

(defn- seller-unverified-violations
  "The target seller (merchant account) must exist AND be independently
  `:registered?`, `:verified?` AND `:payment-processor-linked?` in the
  store -- never trust the proposal's own `:seller-id` claim without a
  seller lookup. This is the e-commerce-specific primary gate: unlike a
  physical-retail sibling's store-verification check, a seller that is
  registered and verified but not yet linked to a payment processor is
  STILL a HARD hold."
  [{:keys [seller-id]} st]
  (let [s (store/seller-record st seller-id)]
    (when-not (and s (:registered? s) (:verified? s) (:payment-processor-linked? s))
      [{:rule :seller-unverified
        :detail (str seller-id " は未登録・未検証、または決済代行会社と未連携の出品者/加盟店アカウント -- いかなる提案も進められない")}])))

(defn- vendor-unverified-violations
  "For `:coordinate-supply-order` ONLY, the proposal's own drafted
  `:value` must name a `:vendor-id` that resolves to an independently
  `:registered?`/`:verified?` inventory vendor record. A missing
  vendor-id, or one that resolves to an unregistered/unverified vendor,
  is a HARD block -- never trust the proposal's own vendor claim without
  a store lookup, the SAME 'ground truth, not self-report' discipline as
  `seller-unverified-violations`, reapplied to the supply-chain
  counterparty."
  [proposal st]
  (when (= :coordinate-supply-order (:op proposal))
    (let [vendor-id (get-in proposal [:value :vendor-id])
          v (and vendor-id (store/vendor-record st vendor-id))]
      (when-not (and v (:registered? v) (:verified? v))
        [{:rule :vendor-unverified
          :detail (str (or vendor-id "(vendor-id missing)")
                        " は未登録または未検証の仕入先 -- 発注調整提案を進められない")}]))))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim to
  directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one lower-cased
  blob the scope-exclusion scan checks."
  [proposal]
  (str/lower-case (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist, or
  one whose content touches directly finalizing a fraud determination, a
  chargeback ruling, or a payment-dispute resolution, regardless of
  confidence or how clean every other check is. Evaluated
  UNCONDITIONALLY on every proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "不正判定・チャージバック裁定・支払い紛争の解決など確定行為(fraud/chargeback/payment-dispute finalization)に触れる提案は永久に禁止"}])))

(defn- high-cost-supply-order?
  "A `:coordinate-supply-order` proposal citing an `:estimated-cost` above
  `supply-cost-threshold` -- always needs human sign-off (SOFT escalate,
  not a hard block: the order itself is in scope, only its size requires
  a human)."
  [proposal]
  (and (= :coordinate-supply-order (:op proposal))
       (some-> proposal :value :estimated-cost (> supply-cost-threshold))))

(defn check
  "Censors a MailOrderRetailAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [seller-id (or (:seller-id proposal) (:seller-id request))
        hard (into []
                   (concat (seller-unverified-violations {:seller-id seller-id} store)
                           (vendor-unverified-violations proposal store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        stakes? (boolean (or (always-escalate-ops (:op proposal))
                              (high-cost-supply-order? proposal)))
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :seller-id  (:seller-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
