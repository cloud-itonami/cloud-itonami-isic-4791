(ns mailorderops.advisor-test
  "Unit tests of `mailorderops.advisor` proposal generation."
  (:require [clojure.test :refer [deftest is testing]]
            [mailorderops.advisor :as adv]
            [mailorderops.store :as store]))

(def db (store/seed-db))

(deftest propose-order-record-shape
  (testing "order-record proposal has correct shape and fields"
    (let [p (adv/infer db {:op :log-order-record
                           :seller-id "seller-1"
                           :patch {:orders-placed 42 :returns 3 :shipments-dispatched 39}})]
      (is (= :log-order-record (:op p)))
      (is (= "seller-1" (:seller-id p)))
      (is (= :propose (:effect p)))
      (is (<= 0 (:confidence p) 1))
      (is (map? (:value p)))
      (is (contains? (:value p) :seller-id)))))

(deftest propose-fulfillment-operation-shape
  (testing "fulfillment-operation proposal has correct shape"
    (let [p (adv/infer db {:op :schedule-fulfillment-operation
                           :seller-id "seller-2"
                           :patch {:window "pick-pack-ship" :date "2026-07-20"}})]
      (is (= :schedule-fulfillment-operation (:op p)))
      (is (= "seller-2" (:seller-id p)))
      (is (= :propose (:effect p))))))

(deftest propose-supply-order-shape
  (testing "supply-order proposal has correct shape"
    (let [p (adv/infer db {:op :coordinate-supply-order
                           :seller-id "seller-1"
                           :patch {:item "inventory restock" :quantity 200 :estimated-cost 480.0
                                   :vendor-id "vendor-1"}})]
      (is (= :coordinate-supply-order (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p)))
      (is (= "vendor-1" (get-in p [:value :vendor-id]))))))

(deftest propose-fraud-concern-shape
  (testing "fraud-concern proposal always escalates"
    (let [p (adv/infer db {:op :flag-fraud-concern
                           :seller-id "seller-1"
                           :patch {:concern "unusual order velocity from a single new shipping address"}})]
      (is (= :flag-fraud-concern (:op p)))
      (is (= :propose (:effect p)))
      (is (string? (:summary p))))))

(deftest all-proposals-effect-is-always-propose
  (testing "every proposal type has :effect :propose, never direct actuation"
    (doseq [op [:log-order-record :schedule-fulfillment-operation :coordinate-supply-order
                :flag-fraud-concern]]
      (let [p (adv/infer db {:op op :seller-id "seller-1" :patch {}})]
        (is (= :propose (:effect p))
            (str "op " op " must have :effect :propose"))))))

(deftest rationale-string-is-present
  (testing "every proposal has a rationale explaining the advisor's thinking"
    (doseq [op [:log-order-record :schedule-fulfillment-operation :coordinate-supply-order
                :flag-fraud-concern]]
      (let [p (adv/infer db {:op op :seller-id "seller-1" :patch {}})]
        (is (string? (:rationale p))
            (str "op " op " must have a :rationale string"))))))
