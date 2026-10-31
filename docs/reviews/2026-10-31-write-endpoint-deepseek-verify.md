# Verification: write endpoint fix round 1

Reviewed `docs/tasks/2026-10-31-write-endpoint-fixes-01.md`, the current diff, `DataWriteController.java`,
`DataWriteApiTest.java`, the README and the updated contract. PostgreSQL 18 facts from the COPY docs /
`copyfrom.c` / `copyfromparse.c`. `websearch` was HTTP 403 again; PostgreSQL behaviour was confirmed via
`webfetch` of the official docs and sources.

## Finding

### 1. `CsvRecordCounter` double-counts CRLF line endings
- `DataWriteController.java:399-423`, specifically the terminator branch
  `else if (b == '\r' || b == '\n') { records++; inRecord = false; }`.
- Concrete failure: the header reader correctly pairs `\r\n` (`readCsvHeader`, `:335-344`, peeks a `\n`
  after `\r` and consumes both as one terminator), but the counter does not — it increments `records` for
  the `\r` and again for the `\n`. A Windows-style CSV (`\r\n`, what Excel and most Windows tools emit)
  therefore reports `rows_received = 2×actual` and `rows_ignored = actual` (phantom "skipped" rows) on a
  successful 200. The data itself lands correctly (PostgreSQL parses `\r\n` as one record), so only the
  counts are silently wrong. No test covers CRLF — every `DataWriteApiTest` body uses `\n` only.
- This also contradicts the contract's own wording, "a record ends at an unquoted line terminator"
  (`docs/tasks/2026-10-31-write-endpoint.md:43-45`), where `\r\n` is one terminator.
- Smallest fix: give `CsvRecordCounter` a `pendingCR` flag mirroring `readCsvHeader`: on an unquoted `\r`
  set `pendingCR` and do not increment yet; on the next byte, `\n` -> one record (CRLF), any other byte ->
  one record (lone CR) then process that byte; a `pendingCR` still set at EOF counts one record.
- Verdict: `fix`.

## Fixes verified correct

- **Item 1 (count from input, not NOTICE):** done, apart from finding 1. The `lc_messages`/`is_superuser`
  code and the warning-chain reader are gone (`DataWriteController.java` has only the three `SET LOCAL`
  statements at `:163-167`); `rows_ignored = received - written` (`:174`). JSON counts objects
  (`copyJson`, `received++` at `:219`/`:227`). Multiple skipped rows are tested
  (`conversionFailuresAreSkippedAndCounted`, `jsonConversionFailuresAreCountedToo`).
- **Item 2 (header state machine):** done. `readCsvHeader` (`:302-359`) implements the measured PostgreSQL
  rule (mid-field quote opens quoting, `""` is a literal quote, CRLF paired) and the tests pin all three
  (`quoteInsideCsvHeaderCellFollowsPostgresCsvRule`, `doubledQuoteInCsvHeaderCellIsALiteralQuote`,
  `quotedNewlineInCsvHeaderIsPartOfTheHeaderRecord`). The "quote only at field start" idea was correctly
  rejected.
- **Item 3 (aggregate/window excluded):** done. `functionKind` filters `p.prokind IN ('f','p')`
  (`:509`); `hasNonCallableRoutine` (`:528-540`) distinguishes "Not a callable function" from "not found".
  Tested with `pg_catalog.count` (`aggregateIsNotACallableFunction`, asserts the message).
- **Item 4 (GUC pins):** done. `SET LOCAL client_encoding='UTF8'`, `datestyle='ISO, YMD'`,
  `intervalstyle='postgres'` run inside the write transaction before the COPY (`:163-167`). The
  `datestyleLeftOnAPooledConnectionDoesNotChangeDateParsing` test poisons the pool via `memory` and asserts
  the stored date, so a removed pin would fail it.
- **Item 5 (DROP IF EXISTS):** done. `:180`.
- **Item 6 (column guards):** done. `validateColumns` (`:447-456`) rejects empty and >63-byte names before
  any DB work (`:148`); tested (`emptyColumnNameIsBadRequest`, `columnNameLongerThan63BytesIsBadRequest`).
- **Item 7 (CORS tests):** done. `corsIsOpenOnQueryButClosedOnData` asserts `Access-Control-Allow-Origin`
  present on `/api/query` and absent on `/api/data` (`DataWriteApiTest.java:461-465`), not just a status.
- **Item 8 (DEFAULT + rollback):** done. `columnDefaultIsKeptWhenTheColumnIsMissingFromTheInput` asserts
  `id = 7`; `functionThatRaisesLeavesNoAutoCreatedTable` asserts the auto-created table is gone.
- **Item 9 (documentation):** done. README has the upgrade order and salt-permanence note
  ("Upgrading a deployed instance"), the `X-Access-Token` variant, header-only-CSV/missing-header wording,
  the function-own-schema and `RETURNS void` shape, and the `/api/`-paths-answer-401 rule; the contract
  (`docs/tasks/2026-10-31-write-endpoint.md:22-86`) carries the same wording.

## Not worth fixing (recorded)

- **Leading UTF-8 BOM:** a BOM at the start of a CSV body is read as ordinary bytes and becomes part of the
  first column name (so an existing table named without the BOM answers 400, an auto-created table gets an
  invisible `\uFEFF`-prefixed column). This is pre-existing, matches PostgreSQL's own COPY (which also does
  not strip a BOM), and the "bytes go to COPY untouched" rule would be contradicted by stripping it.
  Not worth fixing.
- **`client_encoding`/`intervalstyle` pins are untested** (only `datestyle` is): the three `SET LOCAL`s are
  the same pattern and `datestyle` exercises the mechanism; the other two are trivially symmetric. Not worth
  fixing.
