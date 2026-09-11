(ns mailorderops.store-contract-test
  "Contract tests for `mailorderops.store/Store` protocol."
  (:require [clojure.test :refer [deftest is testing]]
            [mailorderops.store :as store]))

(deftest mem-store-seller-lookup
  (testing "MemStore can store and retrieve sellers by ID (string keys)"
    (let [sellers {"s1" {:seller-id "s1" :name "Alice's Mail-Order Shop" :registered? true :verified? true :payment-processor-linked? true}}
          s (store/mem-store sellers)]
      (is (some? (store/seller-record s "s1")))
      (is (nil? (store/seller-record s "s99"))))))

(deftest mem-store-all-seller-records
  (testing "MemStore returns all sellers in sorted order"
    (let [sellers {"s2" {:seller-id "s2" :name "Bob's Variety Web Shop"}
                   "s1" {:seller-id "s1" :name "Alice's Mail-Order Shop"}
                   "s3" {:seller-id "s3" :name "Carol's Department Web Store"}}
          s (store/mem-store sellers)
          all-s (store/all-seller-records s)]
      (is (= 3 (count all-s)))
      (is (= "s1" (:seller-id (first all-s))))
      (is (= "s3" (:seller-id (last all-s)))))))

(deftest mem-store-vendor-lookup
  (testing "MemStore can store and retrieve vendors by ID (string keys)"
    (let [vendors {"v1" {:vendor-id "v1" :name "Acme Supply" :registered? true :verified? true}}
          s (store/mem-store {} vendors)]
      (is (some? (store/vendor-record s "v1")))
      (is (nil? (store/vendor-record s "v99"))))))

(deftest mem-store-all-vendor-records
  (testing "MemStore returns all vendors in sorted order"
    (let [vendors {"v2" {:vendor-id "v2" :name "Beta Supply"}
                   "v1" {:vendor-id "v1" :name "Acme Supply"}}
          s (store/mem-store {} vendors)
          all-v (store/all-vendor-records s)]
      (is (= 2 (count all-v)))
      (is (= "v1" (:vendor-id (first all-v)))))))

(deftest mem-store-ledger-append
  (testing "MemStore append-ledger! adds facts to immutable log"
    (let [s (store/mem-store {})
          fact1 {:t :test :data "fact1"}
          fact2 {:t :test :data "fact2"}]
      (is (= 0 (count (store/ledger s))))
      (store/append-ledger! s fact1)
      (is (= 1 (count (store/ledger s))))
      (store/append-ledger! s fact2)
      (is (= 2 (count (store/ledger s)))))))

(deftest mem-store-coordination-log
  (testing "MemStore commit-record! appends to coordination-log"
    (let [s (store/mem-store {})
          record {:op :log-order-record :seller-id "s1" :value {:orders-placed 42}}]
      (is (= 0 (count (store/coordination-log s))))
      (store/commit-record! s record)
      (is (= 1 (count (store/coordination-log s))))
      (is (= record (first (store/coordination-log s)))))))

(deftest mem-store-with-seller-records
  (testing "MemStore with-seller-records replaces the seller directory"
    (let [s (store/mem-store {})
          new-sellers {"s1" {:seller-id "s1" :name "Alice's Mail-Order Shop"}}]
      (is (= 0 (count (store/all-seller-records s))))
      (store/with-seller-records s new-sellers)
      (is (= 1 (count (store/all-seller-records s)))))))

(deftest mem-store-with-vendor-records
  (testing "MemStore with-vendor-records replaces the vendor directory"
    (let [s (store/mem-store {})
          new-vendors {"v1" {:vendor-id "v1" :name "Acme Supply"}}]
      (is (= 0 (count (store/all-vendor-records s))))
      (store/with-vendor-records s new-vendors)
      (is (= 1 (count (store/all-vendor-records s)))))))

(deftest seed-db-has-demo-data
  (testing "seed-db creates a populated MemStore with demo sellers and vendors"
    (let [s (store/seed-db)]
      (is (> (count (store/all-seller-records s)) 0))
      (is (some? (store/seller-record s "seller-1")))
      (is (some? (store/seller-record s "seller-2")))
      (is (some? (store/seller-record s "seller-3")))
      (is (some? (store/seller-record s "seller-4")))
      (is (> (count (store/all-vendor-records s)) 0))
      (is (some? (store/vendor-record s "vendor-1")))
      (is (some? (store/vendor-record s "vendor-2"))))))

(deftest demo-data-string-key-consistency
  (testing "demo-data uses string keys, not keywords, for seller-id/vendor-id"
    (let [demo (store/demo-data)
          sellers (:sellers demo)
          vendors (:vendors demo)]
      (doseq [[k v] sellers]
        (is (string? k) "seller keys must be strings")
        (is (string? (:seller-id v)) "seller-id must be string")
        (is (= k (:seller-id v)) "key must match seller-id"))
      (doseq [[k v] vendors]
        (is (string? k) "vendor keys must be strings")
        (is (string? (:vendor-id v)) "vendor-id must be string")
        (is (= k (:vendor-id v)) "key must match vendor-id")))))

(deftest demo-data-payment-processor-linked-field-present
  (testing "every demo seller record has an explicit :payment-processor-linked? boolean field (the e-commerce-specific verification dimension)"
    (let [sellers (:sellers (store/demo-data))]
      (doseq [[_ v] sellers]
        (is (boolean? (:payment-processor-linked? v))
            (str (:seller-id v) " must have an explicit :payment-processor-linked? boolean"))))))

(deftest store-is-append-only
  (testing "appended facts are immutable and never removed"
    (let [s (store/seed-db)
          fact1 {:t :event1 :data "a"}
          fact2 {:t :event2 :data "b"}]
      (store/append-ledger! s fact1)
      (let [ledger-after-1 (store/ledger s)]
        (store/append-ledger! s fact2)
        (let [ledger-after-2 (store/ledger s)]
          (is (= (count ledger-after-1) (dec (count ledger-after-2))))
          (is (every? #(some (fn [x] (= x %)) ledger-after-2) ledger-after-1)
              "all prior facts must still be present"))))))
