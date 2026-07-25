# e2e-tests — the executable spec of the end-to-end flow

`PaymentFlowE2EIntegrationTest` boots the whole topology as real containers and drives createPayment → authorize, asserting every milestone to `Payment.status=SETTLED`. **To understand what happens across modules (the thing architecture.md's diagrams describe), read this test — its assertions are verified truth, not prose that can drift.**

## The flow = milestones M0..M13 (each pins one module's job)
- **M0** intent CREATED, NO outbox event yet — `payment-service` (create writes no outbox).
- **M1–M2** edge `outbox_event(payment_authorized)` row + intent AUTHORIZED — `payment-service` (authorize).
- **M3** edge outbox row → `SENT` — `payment-edge-workers` forwarded it.
- **M4–M5** central `outbox_event` has the payload → `SENT` — `payment-central-relay` (T_Safe gate + publish).
- **M6–M13** central `Payment` AUTHORIZED → SENT_FOR_SETTLE → CAPTURED → SETTLED; `journal_entries` of type AUTHORIZATION/CAPTURE/INTERNAL_TRANSFER/SETTLEMENT; `transfers` to SELLER-5-1/SELLER-5-2 — `payment-consumers` (ledger).

## Facts to know before touching it
- **Not a reactor module** (root pom intentionally omits it — listing it breaks the service image builds). Run standalone: `mvn -f e2e-tests/pom.xml verify`.
- **Reuses published images** `dcaglar1987/<module>:latest` (no in-test build, no app-source e2e config). Uses `SPRING_PROFILES_ACTIVE=local` + `psp.gateway.type=SIMULATED`; `MARKETPLACE-5` is the hardcoded sim target that auto-drives the full SETTLED chain.
- Detailed design/run/drift notes live in memory `e2e-test-flow-facts`; known drifts: real capture topic `gateway.capture.requested`; eventType `settlement_received`.
