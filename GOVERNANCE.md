# Governance

`cloud-itonami-isic-4791` is an OSS open-business blueprint for
mail-order/e-commerce-retail operations coordination (ISIC Rev.5 4791 --
retail sale via mail order houses or via Internet).

## Maintainers
Maintainers may merge changes that preserve these invariants:
- a proposal for an unverified/unregistered/payment-processor-unlinked
  seller, or a supply order naming an unverified/unregistered vendor,
  can never commit.
- the MailOrderRetailGovernor remains independent of the advisor.
- hard policy violations (non-`:propose` effect, fraud-determination/
  chargeback-ruling/payment-dispute-resolution finalization content, an
  op outside the closed allowlist) cannot be overridden by human
  approval.
- every order-record log, fulfillment-operation schedule, supply-order
  coordination and fraud-concern flag is auditable.
- customer, employee and supplier data stays outside Git.

## Decision Records
Architecture decisions live in `docs/adr/`. Changes to the trust model,
storage contract, public business model, operator certification or
license should add or update an ADR.

## Operator Governance
Anyone may fork and operate independently. itonami.cloud certification is
a separate trust mark and should require security, audit and data-flow
review.

Certified operators can lose certification for:
- bypassing order-record, fulfillment, supply-order or fraud-concern
  policy checks
- mishandling customer, employee or supplier data
- misrepresenting certification status
- failing to respond to security or fraud/chargeback incidents

## License
AGPL-3.0-or-later.
