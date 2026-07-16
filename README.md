# cloud-itonami-isic-4791

Open Business Blueprint for **ISIC Rev.5 4791**: retail sale via mail
order houses or via Internet -- mail-order and e-commerce retail with
**NO physical storefront**, distinct from every physical-retail sibling
in this fleet (e.g. ISIC 4719's general-merchandise stores, ISIC 4711's
predominantly-food community retail).

This repository publishes a mail-order/e-commerce-retail
operations-COORDINATION actor -- order/shipment/return transaction
logging, warehouse fulfillment (pick/pack/ship) scheduling, inventory
supply-order coordination with registered vendors, and suspected-fraud/
chargeback/payment-dispute-concern flagging -- as an OSS business that
any qualified operator can fork, deploy, run, improve and sell, so an
independent mail-order/Internet retailer never surrenders its operations
data to a closed back-office SaaS.

Built on this workspace's
[`langgraph`](https://github.com/kotoba-lang/langgraph)
StateGraph runtime (portable `.cljc`, supervised superstep loop,
interrupts, in-mem/Datomic checkpoints) -- the same actor pattern as
every prior actor in this fleet -- here it is **MailOrderRetailAdvisor
⊣ MailOrderRetailGovernor**. This blueprint's own
`:itonami.blueprint/governor` keyword, `:mail-order-retail-governor`, is
a distinct, independent build (no naming-collision precedent question --
distinct from ISIC 4719's own `:merchandise-retail-governor` and ISIC
4711's own `:retail-governor`).

> **Why an actor layer at all?** An LLM is great at drafting an
> order-record summary, a fulfillment-scheduling proposal, or a supply-
> order request -- but it has no license to actually rule on a fraud
> determination, no way to independently confirm a seller is actually a
> registered/verified e-commerce merchant account linked to a payment
> processor, or a supply-order vendor is actually a registered/verified
> counterparty, and no notion of when a "flag this concern" op quietly
> turns into a claim to have already resolved it. Letting it act
> directly invites an unverified/unlinked merchant account entering the
> ledger, an unverified vendor receiving an inventory order, or -- worst
> of all -- a fabricated claim to have finalized a fraud determination or
> a chargeback ruling, exposing the seller and the platform operator to
> real financial and legal liability. This project seals the
> MailOrderRetailAdvisor into a single node and wraps it with an
> independent **MailOrderRetailGovernor**, a human **approval
> workflow**, and an immutable **audit ledger**.

## Scope: coordination only, not fraud/chargeback/dispute authority

This actor is **operations coordination only**. It never performs or
authorizes:

- directly finalizing a fraud determination (ruling an order or account
  fraudulent)
- directly finalizing a chargeback ruling (approving, denying or
  reversing a chargeback)
- directly finalizing a payment-dispute resolution (closing a dispute in
  either party's favor)

The governor's `scope-exclusion-violations` check re-scans every
proposal for this failure mode independently of the advisor's own
framing, and treats it as a HARD, permanent block regardless of
confidence or how clean everything else is. Flagging a suspected-fraud/
chargeback/payment-dispute concern for a human to triage is exactly this
actor's job -- `:flag-fraud-concern` is never excluded by this check,
only FINALIZING/ruling-on/resolving that concern is.

### Actuation

**Every proposal this actor generates is `:effect :propose`, never a
direct actuation.** Two independent layers enforce this
(`mailorderops.governor`'s `effect-not-propose-violations` HARD check and
`mailorderops.phase`'s phase table, which never puts
`:flag-fraud-concern` in any phase's `:auto` set). A human mail-order
operations coordinator is always the one who actually resolves a flagged
fraud/chargeback/payment-dispute concern or confirms a high-cost supply
order.

## The core contract

```
seller/vendor registration + operations-coordination request
        |
        v
   ┌───────────────────────┐   proposal      ┌────────────────────────────┐
   │ MailOrderRetail-      │ ─────────────▶ │ MailOrderRetailGovernor      │  (independent system)
   │ Advisor (sealed)      │  + citations    │ seller-unverified            │
   └───────────────────────┘                 │  (registered+verified+       │
          │                 commit ◀┼ payment-processor-linked) ·          │
          │                         │ vendor-unverified ·                  │
    record + ledger        escalate ┼ effect-not-propose ·                 │
          │              (ALWAYS for│ scope-excluded (fraud/chargeback/    │
          │       :flag-fraud-      │ payment-dispute finalization) ·      │
          │       concern/high-cost │ op-not-allowed                       │
          │       supply-order)     └────────────────────────────┘
          ▼
      human approval
```

**The MailOrderRetailAdvisor never commits a proposal the
MailOrderRetailGovernor would reject, and a fraud-concern flag or a
high-cost supply order never commits without a human sign-off.** Hard
violations (an unregistered/unverified/payment-processor-unlinked
seller; an unregistered/unverified supply-order vendor; a non-`:propose`
effect; content touching fraud/chargeback/payment-dispute finalization;
an op outside the closed allowlist) force **hold** and *cannot* be
approved past.

## Robotics premise

All cloud-itonami verticals are designed on the premise that a **robot
may perform physical domain work** (here: warehouse picking, packing,
palletizing) under human/robot fulfillment-center operations gated by
seller policy. This actor itself does not dispatch robot/hardware
actions -- it is strictly the operations-coordination layer
(order-record logging, fulfillment-scheduling coordination, supply-order
coordination, fraud-concern flagging) any physical-dispatch layer could
eventually feed proposals into, always gated the same way by the
independent MailOrderRetailGovernor.

## Features

- **Closed proposal-op allowlist**: `log-order-record`,
  `schedule-fulfillment-operation`, `coordinate-supply-order`,
  `flag-fraud-concern` (all `:effect :propose`). CRITICAL: no op that
  directly finalizes a fraud determination, a chargeback ruling, or a
  payment-dispute resolution is EVER a member of this allowlist.
- **Four HARD governor checks** (permanent, un-overridable):
  1. **Seller/merchant-account unverified** (E-COMMERCE-SPECIFIC PRIMARY
     GATE) -- the target seller's business registration must exist AND
     be independently `:registered?`, `:verified?` AND
     `:payment-processor-linked?`. A seller mid-onboarding (registered
     and verified but not yet linked to a payment processor) is treated
     exactly like an unverified seller -- this replaces the physical-
     retail "store verification" gate a sibling like ISIC 4719 uses,
     since this actor has no physical storefront to verify.
  2. **Vendor unverified** -- for `:coordinate-supply-order` only, the
     named inventory vendor must exist AND be independently
     registered/verified.
  3. **Effect is :propose** -- any other `:effect` value is rejected.
  4. **Scope exclusion** (PAYMENT-FRAUD/CHARGEBACK DIMENSION) --
     directly finalizing a fraud determination, a chargeback ruling, or
     a payment-dispute resolution, and an op outside the closed
     allowlist, are both permanently blocked.
- **Two ESCALATE (SOFT) gates**, either forces human sign-off:
  - `:flag-fraud-concern` -- ALWAYS escalates, regardless of confidence
    or phase. A "flag a concern" op is never auto-commit eligible and
    never finalizes a fraud/chargeback/payment-dispute decision itself
    -- it only surfaces the concern for a human.
  - `:coordinate-supply-order` above a cost threshold -- a large-value
    procurement proposal always needs a human sign-off.
  - (LLM confidence below the floor also escalates, as with every
    sibling actor.)
- **Staged rollout** (Phase 0→3):
  - Phase 0: read-only
  - Phase 1: order-record logging only (approval-gated)
  - Phase 2: + fulfillment-operation scheduling, supply-order proposals
    (approval-gated)
  - Phase 3: auto-commits clean, high-confidence, low-cost proposals
    (fraud concerns and high-cost supply orders always escalate)
- **Append-only audit ledger** -- every decision is an immutable log
  entry.
- **langgraph-clj StateGraph** -- one request = one supervised run;
  human-in-the-loop via `interrupt-before`.

### Development

```bash
# Install dependencies (if inside the superproject, use :dev alias for local overrides)
clojure -M:dev -P

# Run tests
clojure -M:test

# Run linter
clojure -M:lint

# Run demo
clojure -M:run
```

### Test suite

- `test/mailorderops/governor_test.clj` -- unit tests of governor hard
  checks, scope exclusion, and the self-trip regression test
- `test/mailorderops/advisor_test.clj` -- advisor proposal shape and
  consistency
- `test/mailorderops/phase_test.clj` -- rollout phase logic
- `test/mailorderops/governor_contract_test.clj` -- full graph
  integration, audit trail
- `test/mailorderops/store_contract_test.clj` -- Store protocol and
  MemStore implementation

### Modules

- `mailorderops.store` -- SSoT (MemStore, String-keyed seller/vendor
  directories, append-only ledger)
- `mailorderops.advisor` -- contained intelligence node (mock + real-LLM
  seam)
- `mailorderops.governor` -- independent compliance layer
- `mailorderops.phase` -- staged rollout (0→3)
- `mailorderops.operation` -- langgraph-clj StateGraph
- `mailorderops.sim` -- demo driver

## Capability layer

This blueprint resolves its technology stack via
[`kotoba-lang/industry`](https://github.com/kotoba-lang/industry) (ISIC
`4791`).

## Business-process coverage (honest)

| Covered | Not covered (out of scope for this R0) |
|---|---|
| Order/shipment/return transaction logging (`:log-order-record`) | Real order-management/inventory-system integration |
| Warehouse fulfillment (pick/pack/ship) scheduling coordination (`:schedule-fulfillment-operation`) | Direct WMS/carrier-label integration |
| Inventory supply-order coordination with a registered, verified vendor, HARD-gated on vendor verification and a double-actuation-free single-proposal shape (`:coordinate-supply-order`) | Real supplier-ordering-system integration |
| Suspected-fraud/chargeback/payment-dispute-concern flagging, ALWAYS human-gated (`:flag-fraud-concern`) | Directly finalizing any fraud determination, chargeback ruling, or payment-dispute resolution -- permanently out of scope, not a gap |
| Immutable audit ledger for every log/schedule/order/flag decision | Payment-processor/gateway integration for real transaction capture -- a follow-up slice, not in this R0 |

Extending coverage is additive: add the next op (e.g. a
return-authorization or a shipping-exception-escalation check) as its
own governed op with its own HARD checks and tests, following the SAME
"an independent governor re-verifies against the actor's own records
before any real-world act" pattern this repo's flagship checks already
establish.

## Maturity

`:implemented` -- `MailOrderRetailAdvisor` + `MailOrderRetailGovernor`
run as real, tested code (see `Development` above), following the SAME
governed-actor architecture as every prior actor across this fleet, with
its own distinct, independently-named governor and its own
e-commerce-specific seller/merchant-account (payment-processor-linked)
verification gate in place of a physical-store-verification gate.

## License

Code and implementation templates are AGPL-3.0-or-later.
