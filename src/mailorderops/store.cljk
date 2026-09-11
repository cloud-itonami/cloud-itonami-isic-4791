(ns mailorderops.store
  "SSoT for the ISIC-4791 'Retail sale via mail order houses or via
  Internet' (mail-order / e-commerce retail -- NO physical storefront)
  operations-COORDINATION actor, behind a `Store` protocol so the backend
  is a swap, not a rewrite -- the same seam every `cloud-itonami-isic-*`
  actor in this fleet uses.

  Unlike a physical-retail sibling (e.g. ISIC 4719), this actor's primary
  gate is NOT a store-verification check -- it is a SELLER/MERCHANT-
  ACCOUNT verification check: a registered, independently-verified
  e-commerce merchant account that is ALSO linked to a payment
  processor. A seller record that is `:registered?`/`:verified?` but not
  yet `:payment-processor-linked?` (e.g. mid-onboarding, awaiting
  processor approval) must HARD-hold exactly like an unregistered seller
  -- see `mailorderops.governor`'s `seller-unverified-violations`.

  This actor coordinates the back-office operations of a mail-order/
  Internet storefront: order/shipment/return transaction logging,
  warehouse fulfillment (pick/pack/ship) scheduling, merchandise/
  inventory supply-order coordination with registered vendors, and
  suspected-fraud/chargeback/payment-dispute-concern flagging. It NEVER
  finalizes a fraud determination, a chargeback ruling, or a
  payment-dispute resolution -- see `mailorderops.governor`'s
  `scope-exclusion-violations`, a HARD, permanent, un-overridable block.

  `MemStore` -- atom of EDN. The deterministic default for dev/tests/demo
  (no deps). A `sellers` directory keyed by `:seller-id` STRING and a
  `vendors` directory keyed by `:vendor-id` STRING (never keywords --
  consistent keying from the start, avoiding the silent-miss bug that has
  plagued earlier sibling actors).

  A registered/verified/payment-processor-linked seller (merchant
  account) record must exist before ANY proposal targeting that seller
  may ever commit or escalate -- `mailorderops.governor`'s
  `seller-unverified-violations` re-derives this from the seller's own
  `:registered?`/`:verified?`/`:payment-processor-linked?` fields, never
  from proposal self-report. A `:coordinate-supply-order` proposal
  additionally names a registered inventory vendor via its own
  `:vendor-id`; the SAME 'ground truth, not self-report' discipline
  applies via `vendor-unverified-violations`.

  The ledger stays append-only: which seller a proposal targeted, which
  operation, on what basis, committed/held/escalated and approved by whom
  is always a query over an immutable log.")

(defprotocol Store
  (seller-record [s seller-id] "Registered seller (merchant-account) record, or nil.
    Seller map: {:seller-id .. :name .. :registered? bool :verified? bool
                 :payment-processor-linked? bool}.")
  (all-seller-records [s])
  (vendor-record [s vendor-id] "Registered inventory-vendor record, or nil.
    Vendor map: {:vendor-id .. :name .. :registered? bool :verified? bool}.")
  (all-vendor-records [s])
  (ledger [s] "the append-only immutable decision-fact log")
  (coordination-log [s] "the append-only committed coordination-proposal history")
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-seller-records [s sellers] "replace/seed the seller directory (map seller-id->seller)")
  (with-vendor-records [s vendors] "replace/seed the vendor directory (map vendor-id->vendor)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained seller/vendor directory covering both the
  happy path and the governor's own hard checks, so the actor + tests
  run offline."
  []
  {:sellers
   {"seller-1" {:seller-id "seller-1" :name "Riverside Mail-Order Co."
                :registered? true :verified? true :payment-processor-linked? true}
    "seller-2" {:seller-id "seller-2" :name "Sunset Boulevard Direct-to-Consumer"
                :registered? true :verified? true :payment-processor-linked? true}
    "seller-3" {:seller-id "seller-3" :name "Downtown Pop-Up Web Storefront (in intake)"
                :registered? true :verified? false :payment-processor-linked? false}
    "seller-4" {:seller-id "seller-4" :name "New Web Storefront Awaiting Payment-Processor Approval"
                :registered? true :verified? true :payment-processor-linked? false}}
   :vendors
   {"vendor-1" {:vendor-id "vendor-1" :name "Northgate Fulfillment Supply"
                :registered? true :verified? true}
    "vendor-2" {:vendor-id "vendor-2" :name "Unverified Import Broker Co."
                :registered? true :verified? false}}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (seller-record [_ seller-id] (get-in @a [:sellers seller-id]))
  (all-seller-records [_] (sort-by :seller-id (vals (:sellers @a))))
  (vendor-record [_ vendor-id] (get-in @a [:vendors vendor-id]))
  (all-vendor-records [_] (sort-by :vendor-id (vals (:vendors @a))))
  (ledger [_] (:ledger @a))
  (coordination-log [_] (:coordination-log @a))
  (commit-record! [_ record]
    (swap! a update :coordination-log conj record)
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-seller-records [s sellers] (when (seq sellers) (swap! a assoc :sellers sellers)) s)
  (with-vendor-records [s vendors] (when (seq vendors) (swap! a assoc :vendors vendors)) s))

(defn seed-db
  "A MemStore seeded with the demo seller/vendor directory. The
  deterministic default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger [] :coordination-log []))))

(defn mem-store
  "A MemStore seeded with explicit `sellers`/`vendors` maps (seller-id/
  vendor-id string -> record map) -- the primary test/dev entry point.
  Either may be empty (an unregistered-everywhere seller)."
  ([sellers] (mem-store sellers {}))
  ([sellers vendors]
   (->MemStore (atom {:sellers (or sellers {}) :vendors (or vendors {})
                       :ledger [] :coordination-log []}))))
