(ns mailorderops.sim
  "Demo driver -- `clojure -M:run`. Walks a clean order-record logging
  request through intake -> advise -> govern -> decide -> approval ->
  commit at phase 1 (assisted-logging, always approval), then re-runs the
  same op at phase 3 (supervised-auto, clean + high confidence ->
  auto-commit), then a fulfillment-operation-scheduling request and a
  low-cost supply-order coordination naming a verified vendor (both
  auto-commit clean at phase 3), then a high-cost supply-order (ALWAYS
  escalates regardless of phase), then a fraud-concern flag (ALWAYS
  escalates, at any phase -- approve, then commit), then HARD-hold
  scenarios: an unregistered seller, a seller registered but not yet
  verified, a seller registered+verified but NOT yet payment-processor-
  linked (the e-commerce-specific flagship check), a supply-order naming
  an unverified vendor, a proposal whose own `:effect` is not `:propose`,
  and a proposal that has drifted into the permanently-excluded fraud/
  chargeback/payment-dispute-finalization scope."
  (:require [langgraph.graph :as g]
            [mailorderops.advisor :as advisor]
            [mailorderops.store :as store]
            [mailorderops.operation :as op]))

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "mail-order-ops-coordinator-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        coordinator-phase-1 {:actor-id "coord-1" :actor-role :mail-order-ops-coordinator :phase 1}
        coordinator-phase-3 {:actor-id "coord-1" :actor-role :mail-order-ops-coordinator :phase 3}
        actor (op/build db)]

    (println "== log-order-record seller-1 (phase 1, escalates -- human approves) ==")
    (let [r (exec-op actor "t1" {:op :log-order-record :seller-id "seller-1"
                                  :patch {:orders-placed 42 :returns 3 :shipments-dispatched 39}} coordinator-phase-1)]
      (println r)
      (println "-- human mail-order ops coordinator approves --")
      (println (approve! actor "t1")))

    (println "\n== log-order-record seller-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t2" {:op :log-order-record :seller-id "seller-1"
                                  :patch {:orders-placed 30 :returns 1 :shipments-dispatched 29}} coordinator-phase-3))

    (println "\n== schedule-fulfillment-operation seller-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t3" {:op :schedule-fulfillment-operation :seller-id "seller-1"
                                  :patch {:window "pick-pack-ship" :date "2026-07-20" :dock "dock-4"}} coordinator-phase-3))

    (println "\n== coordinate-supply-order seller-1, low cost, verified vendor (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t4" {:op :coordinate-supply-order :seller-id "seller-1"
                                  :patch {:item "inventory restock" :quantity 200 :estimated-cost 480.0
                                          :vendor-id "vendor-1"}} coordinator-phase-3))

    (println "\n== coordinate-supply-order seller-1, HIGH cost (ALWAYS escalates, even at phase 3) ==")
    (let [r (exec-op actor "t5" {:op :coordinate-supply-order :seller-id "seller-1"
                                 :patch {:item "seasonal inventory build" :quantity 20 :estimated-cost 3800.0
                                         :vendor-id "vendor-1"}} coordinator-phase-3)]
      (println r)
      (println "-- human mail-order ops coordinator reviews & approves --")
      (println (approve! actor "t5")))

    (println "\n== flag-fraud-concern seller-1 (ALWAYS escalates, even at phase 3) ==")
    (let [r (exec-op actor "t6" {:op :flag-fraud-concern :seller-id "seller-1"
                                 :patch {:concern "unusual order velocity from a single new shipping address, chargeback notice received on a prior order" :confidence 0.92}} coordinator-phase-3)]
      (println r)
      (println "-- human mail-order ops coordinator reviews & approves --")
      (println (approve! actor "t6")))

    (println "\n== log-order-record seller-99 (unregistered seller -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :log-order-record :seller-id "seller-99"
                                  :patch {:orders-placed 0}} coordinator-phase-3))

    (println "\n== log-order-record seller-3 (registered but unverified -> HARD hold) ==")
    (println (exec-op actor "t8" {:op :log-order-record :seller-id "seller-3"
                                  :patch {:orders-placed 10}} coordinator-phase-3))

    (println "\n== log-order-record seller-4 (registered+verified but NOT payment-processor-linked -> HARD hold) ==")
    (println (exec-op actor "t8b" {:op :log-order-record :seller-id "seller-4"
                                   :patch {:orders-placed 10}} coordinator-phase-3))

    (println "\n== coordinate-supply-order seller-1, vendor-2 unverified (-> HARD hold) ==")
    (println (exec-op actor "t9" {:op :coordinate-supply-order :seller-id "seller-1"
                                  :patch {:item "import general merchandise" :quantity 50 :estimated-cost 300.0
                                          :vendor-id "vendor-2"}} coordinator-phase-3))

    (println "\n== schedule-fulfillment-operation seller-1, advisor attempts direct actuation (:effect :commit) -> HARD hold ==")
    (let [actor-direct (op/build db {:advisor (reify advisor/Advisor
                                                (-advise [_ _ req]
                                                  (assoc (advisor/infer nil req) :effect :commit)))})]
      (println (exec-op actor-direct "t10" {:op :schedule-fulfillment-operation :seller-id "seller-1"
                                           :patch {:window "pick-pack-ship" :date "2026-07-22"}} coordinator-phase-3)))

    (println "\n== log-order-record seller-1, advisor drifts into fraud/chargeback-finalization scope -> HARD hold, permanent ==")
    (println (exec-op actor "t11" {:op :log-order-record :seller-id "seller-1"
                                   :out-of-scope? true
                                   :patch {}} coordinator-phase-3))

    (println "\n== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "\n== committed coordination log ==")
    (doseq [r (store/coordination-log db)] (println r))))
