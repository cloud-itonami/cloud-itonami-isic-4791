(ns mailorderops.operation-graph-test
  "Integration tests for `mailorderops.operation/build` -- proves the REAL
  compiled `langgraph.graph` StateGraph runs end-to-end via
  `langgraph.graph/run*` through commit / hard-hold / escalate-approve /
  escalate-reject routes. No prior test file in this repo exercised
  `operation/build` at all -- every other test covers
  governor/phase/advisor/store in isolation, which proves those pure
  functions work but not that the graph wiring
  (`set-entry-point`/`add-conditional-edges`/`compile-graph`/
  `interrupt-before`) actually threads them together."
  (:require [clojure.test :refer [deftest is testing]]
            [langgraph.graph :as g]
            [mailorderops.operation :as operation]
            [mailorderops.store :as store]))

(def ^:private op-context {:actor-id "operator-01" :phase 3})

(defn- exec
  ([actor tid request] (exec actor tid request op-context))
  ([actor tid request context]
   (g/run* actor {:request request :context context} {:thread-id tid})))

(deftest commit-path-log-order-record-auto-commits-in-phase-3
  (testing ":log-order-record is in phase-3's :auto set -- a clean
            proposal for a registered/verified/payment-linked seller
            commits straight through the REAL compiled graph with no
            interrupt, and the ledger is verified EMPTY before the run
            so the post-run fact is genuinely this run's own effect"
    (let [s (store/seed-db)
          actor (operation/build s)]
      (is (empty? (store/ledger s)))
      (let [result (exec actor "t-commit"
                         {:op :log-order-record :seller-id "seller-1"
                          :patch {:order-id "ord-1" :kind :order-placed}})
            state (:state result)]
        (is (= :done (:status result)))
        (is (= :commit (:disposition state)))
        (let [ledger (store/ledger s)]
          (is (= 1 (count ledger)))
          (is (= :committed (:t (first ledger))))
          (is (= :log-order-record (:op (first ledger)))))
        (is (= 1 (count (store/coordination-log s))))))))

(deftest hard-hold-seller-unverified-blocks-before-escalation
  (testing "seller-3 is registered but NOT verified and NOT payment-
            processor-linked -- a HARD governor violation. The real
            graph routes straight to :hold, never pausing for human
            approval"
    (let [s (store/seed-db)
          actor (operation/build s)]
      (is (empty? (store/ledger s)))
      (let [result (exec actor "t-hold"
                         {:op :log-order-record :seller-id "seller-3"
                          :patch {:order-id "ord-2" :kind :order-placed}})
            state (:state result)]
        (is (= :done (:status result)) "no interrupt -- HARD holds never pause for approval")
        (is (= :hold (:disposition state)))
        (let [ledger (store/ledger s)]
          (is (= 1 (count ledger)))
          (is (= :governor-hold (:t (first ledger))))
          (is (some #{:seller-unverified} (map :rule (:violations (first ledger))))))
        (is (empty? (store/coordination-log s)) "never committed to the SSoT")))))

(deftest hard-hold-vendor-unverified-through-compiled-graph
  (testing "vendor-2 is registered but NOT verified -- a
            :coordinate-supply-order naming it is a HARD block, proven
            end-to-end through the compiled graph, distinct from the
            seller-unverified check"
    (let [s (store/seed-db)
          actor (operation/build s)
          result (exec actor "t-vendor-hold"
                       {:op :coordinate-supply-order :seller-id "seller-1"
                        :patch {:vendor-id "vendor-2" :estimated-cost 200.0}})
          state (:state result)]
      (is (= :done (:status result)))
      (is (= :hold (:disposition state)))
      (let [ledger (store/ledger s)]
        (is (= 1 (count ledger)))
        (is (some #{:vendor-unverified} (map :rule (:violations (first ledger)))))))))

(deftest escalate-then-approve-commits-and-genuinely-consults-advisor
  (testing ":flag-fraud-concern is NEVER in any phase's :auto set, so
            even a Governor-clean proposal GENUINELY interrupts
            (checkpointed) at :request-approval -- the ledger stays
            EMPTY until a human resumes it. Also proves the Advisor's
            real proposal (a randomly generated, single-use :concern
            string, impossible to have been hardcoded in
            mailorderops.operation) threads through
            :advise -> :govern -> :decide -> :request-approval -> :commit"
    (let [distinctive-concern (str "TEST-CONCERN-" (rand-int 1000000000))
          s (store/seed-db)
          actor (operation/build s)]
      (is (empty? (store/ledger s)))
      (let [held (exec actor "t-escalate"
                       {:op :flag-fraud-concern :seller-id "seller-1"
                        :patch {:concern distinctive-concern}})]
        (is (= :interrupted (:status held)))
        (is (= [:request-approval] (:frontier held)))
        (is (empty? (store/ledger s)) "not yet committed -- awaiting human sign-off")
        (let [approved (g/run* actor {:approval {:status :approved :by "trust-safety-01"}}
                               {:thread-id "t-escalate" :resume? true})
              approved-state (:state approved)]
          (is (= :done (:status approved)))
          (is (= :commit (:disposition approved-state)))
          (let [ledger (store/ledger s)]
            (is (= 1 (count ledger)))
            (is (= :committed (:t (first ledger))))
            (is (= :flag-fraud-concern (:op (first ledger)))))
          (let [[record] (store/coordination-log s)]
            (is (= distinctive-concern (:concern (:payload record)))
                "the committed record carries the INJECTED distinctive
                concern string -- proof the graph genuinely threads the
                Advisor's real proposal through rather than hardcoding
                a pass-string")))))))

(deftest escalate-then-reject-holds
  (testing "a human trust & safety reviewer rejecting an escalated
            :flag-fraud-concern routes to :hold via the
            :request-approval node's own decision, and durably records
            the rejection -- not a hand-rolled parallel path"
    (let [s (store/seed-db)
          actor (operation/build s)
          _held (exec actor "t-reject"
                      {:op :flag-fraud-concern :seller-id "seller-2"
                       :patch {:concern "unusual order velocity"}})
          rejected (g/run* actor {:approval {:status :rejected :by "trust-safety-01"}}
                           {:thread-id "t-reject" :resume? true})
          rejected-state (:state rejected)]
      (is (= :done (:status rejected)))
      (is (= :hold (:disposition rejected-state)))
      (let [ledger (store/ledger s)]
        (is (= 1 (count ledger)))
        (is (= :approval-rejected (:t (first ledger))))))))

(deftest high-cost-supply-order-escalates-never-auto-commits-even-in-phase-3
  (testing "a :coordinate-supply-order above supply-cost-threshold is a
            SOFT escalate (high-stakes) even though the op itself is in
            phase-3's :auto set and the vendor is genuinely verified --
            proving the cost-threshold gate is folded into the compiled
            graph's :govern node, not merely tested in isolation"
    (let [s (store/seed-db)
          actor (operation/build s)
          result (exec actor "t-highcost"
                       {:op :coordinate-supply-order :seller-id "seller-1"
                        :patch {:vendor-id "vendor-1" :estimated-cost 5000.0}})]
      (is (= :interrupted (:status result))
          "high-cost supply order never auto-commits regardless of phase")
      (is (= [:request-approval] (:frontier result)))
      (is (empty? (store/ledger s))))))
