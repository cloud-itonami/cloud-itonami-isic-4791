# Operator Guide

## First Deployment
1. Register operator, sellers (merchant accounts) and vendors;
   independently confirm each seller's business registration, identity
   verification AND payment-processor linkage, and each vendor's
   registration, before seeding `mailorderops.store`.
2. Import existing order/shipment/return, fulfillment and supply-order
   history.
3. Run read-only order-record-logging and fulfillment-operation dry-runs
   (Phase 0-1).
4. Configure the rollout phase and the `coordinate-supply-order`
   cost-escalation threshold for human sign-off paths.
5. Publish a dry-run fraud-concern flag and audit export.

## Minimum Production Controls
- seller-registration/verification/payment-processor-linkage check
  before ANY proposal for that seller
- vendor-registration/verification check before ANY `:coordinate-
  supply-order` proposal
- governor gate on every proposal before commit
- human sign-off for `:flag-fraud-concern` (always) and high-cost
  `:coordinate-supply-order` proposals
- audit export for every commit, hold and approval
- backup manual back-office process

## Certification
Certified operators must prove seller/vendor-verification discipline,
governor-bypass resistance, evidence-backed fraud/chargeback/
payment-dispute-concern reporting and human review for every
escalation-gated action.
