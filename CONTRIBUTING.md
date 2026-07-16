# Contributing

`cloud-itonami-isic-4791` accepts contributions to the OSS blueprint,
capability bindings, policy tests, documentation and operator model.

## Development

```bash
clojure -M:test
clojure -M:lint
```

## Rules
- Do not commit real customer, employee, supplier or fraud/chargeback-
  incident data.
- Keep order-record logging, fulfillment-operation scheduling,
  supply-order coordination and fraud-concern flagging behind the
  MailOrderRetailGovernor.
- Treat mail-order/e-commerce-retail-operations workflows as high-risk:
  add tests for seller/vendor verification, effect discipline, scope
  exclusion, escalation and audit logging.
- Never phrase a governor scope-exclusion term as a bare noun (e.g.
  "fraud", "chargeback") -- phrase it as the finalization/resolution
  ACTION (e.g. "finalized the chargeback ruling"), and add/extend the
  `default-mock-advisor-proposals-never-self-trip-scope-exclusion`
  regression test for any new term. A bare-noun term will self-trip this
  actor's own legitimate `:flag-fraud-concern` happy path -- see
  `mailorderops.governor/scope-excluded-terms`'s docstring.
- Document any new business-model or operator assumption in `docs/`.

## Pull Requests
PRs should describe: what behavior changed, which policy invariant is
affected, how it was tested, whether operator or certification docs need
updates.
