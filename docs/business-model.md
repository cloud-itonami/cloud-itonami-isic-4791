# Business Model: Mail-Order/E-Commerce Retail Operations Coordination

## Classification
- Repository: `cloud-itonami-isic-4791`
- ISIC Rev.5: `4791` -- retail sale via mail order houses or via
  Internet (NO physical storefront; distinct from every physical-retail
  sibling in this fleet, e.g. ISIC 4719/4711)
- Social impact: local economy, consumer protection, transparency

## Customer
- independent mail-order/e-commerce retailers needing an auditable
  operations-coordination platform
- multi-channel sellers needing consistent fulfillment/supply-order/
  fraud governance across storefronts
- programs that cannot accept closed, unauditable back-office platforms

## Offer
- order/shipment/return transaction logging
- warehouse fulfillment (pick/pack/ship) scheduling coordination
- inventory supply-order coordination with registered, verified vendors
- suspected-fraud/chargeback/payment-dispute-concern flagging for human
  triage
- role-based access and immutable audit ledger

## Revenue
- self-host setup fee
- managed hosting subscription per seller
- support retainer with SLA

## Trust Controls
- `:mail-order-retail-governor` never lets a proposal for an
  unregistered/unverified/payment-processor-unlinked seller, or a supply
  order naming an unregistered/unverified vendor, commit or even
  escalate
- every proposal's `:effect` must be `:propose` -- a claim to directly
  actuate is a HARD, un-overridable block
- directly finalizing a fraud determination, a chargeback ruling, or a
  payment-dispute resolution is permanently out of scope, not a rollout
  milestone -- the actor may only flag a concern for a human
- a `:flag-fraud-concern` proposal, and a high-cost
  `:coordinate-supply-order`, always require human sign-off
- sensitive customer, employee and supplier data stays outside Git
