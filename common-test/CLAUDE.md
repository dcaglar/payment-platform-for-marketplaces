# common-test — shared test fixtures

Test-only helper module (no `src/main`). Provides builders for domain aggregates so tests don't hand-assemble valid graphs.

- `PaymentTestHelper`, `JournalEntryTestHelper` — factory/builder helpers that produce valid `Payment` / `JournalEntry` (and their VOs/postings) for use across modules' unit + integration tests.
- Consumed at **test scope** by other modules. Nothing here ships in a runtime artifact.

## Rules
- When a domain aggregate's `createNew`/`rehydrate` signature changes, update these helpers — stale helpers here cascade into many modules' test failures.
- Keep helpers producing **valid-by-default** objects (satisfy the aggregate invariants); expose params only for the fields a test actually varies.
- Don't put production logic here; it's a test dependency only.
