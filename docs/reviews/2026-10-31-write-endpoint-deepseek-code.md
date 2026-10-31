# Code review: bulk write endpoint (`POST /api/data`)

Reviewed the uncommitted implementation: `DataWriteController.java`, `SqlErrors.java`, the diff to
`TokenAuthFilter`, `AuthService`, `MemoryConfig`, `WebConfig`, `ReadOnlyQueryController`,
`MemoryMcpServerTools`, and the tests `DataWriteApiTest` + `MemoryConfigTest`, plus the implementation
report `docs/tasks/2026-10-31-write-endpoint-implementation.md`.

PostgreSQL 18 facts below are from the official COPY docs and REL_18_STABLE `copyfrom.c` /
`copyfromparse.c` (the summary NOTICE, `ON_ERROR` semantics, ragged-row hard error). `websearch` was
HTTP 403 again, so those were verified by `webfetch`.

## Findings

### 1. `rows_ignored` is parsed out of a translatable NOTICE and can silently report 0
- `DataWriteController.java:56`, `:159-163`, `:446-454`. The NOTICE is the PG 18 summary
  `errmsg_plural("%" PRIu64 " row was skipped due to data type incompatibility", ...)` — a translatable,
  human-readable sentence. `skippedRows` takes the leading integer (`^\s*(\d+)`) and returns `0` when no
  leading integer is found.
- Concrete failure: `SET LOCAL lc_messages='C'` is superuser-only and is skipped for the production agent
  roles (`:159-163`). On a database whose `lc_messages` is not English and whose translation does not
  place the count as the leading token, `matcher.find()` fails and `skippedRows` returns `0`, so the
  response reports `rows_ignored = 0` and `rows_received = rows_written` with no error. The implementation
  report's claim "every translation keeps the leading count" is unverified and is the whole load-bearing
  assumption. The deployed DB is en_US, so it is correct *there* only by luck of the locale.
- There is no locale-independent structured source for the CSV skip count at the same cost. But the JSON
  path *does* have one for free: the server iterates every object in `copyJson` (`:218-223`) yet never
  counts them, so it relies on the same fragile NOTICE even though `rows_received` (and thus
  `rows_ignored = rows_received - rows_written`) is exact and locale-independent for JSON.
- Smallest fix: in `copyJson`, count objects and derive `rows_received` from that count instead of the
  NOTICE; for CSV, either count records server-side (the header reader already splits records) or, when a
  NOTICE is present but `^\s*(\d+)` does not match, fail loud rather than returning `0`.
- Verdict: `fix`.

### 2. A procedure that commits breaks the "nothing lands on failure" guarantee
- `DataWriteController.java:402-404` (`statement.execute("CALL " + qualified)`), `:175-181` (drop + commit).
- Concrete failure: PostgreSQL procedures may issue `COMMIT`/`ROLLBACK` (functions cannot). A procedure
  that `COMMIT`s — then optionally `RAISE`s — commits the `COPY` rows and the auto-created table *before*
  the server's drop/commit runs. The server then answers `400` on the raise, but the rows/table are already
  committed and visible, violating the README guarantee "a failure anywhere rolls everything back — nothing
  lands" (`README.md`, `POST /api/data` section). There is no cheap way to detect that the procedure left
  the transaction.
- Smallest fix: document in the tool description and README that a called routine must not commit/rollback
  (and note that the transaction guarantee only holds for routines that don't); alternatively reject
  procedures and keep only functions.
- Verdict: `fix`.

### 3. No test asserts `/api/data` is CORS-excluded (and the preflight assertion is too weak)
- `WebConfig.java:29` narrows `addMapping("/api/**")` to `addMapping("/api/query")`. The only CORS test,
  `AuthFilterTest.java:120-132`, targets `/api/query` and asserts only `statusCode() != 401` — it never
  asserts the CORS headers, and nothing asserts `/api/data` is closed.
- Concrete failure: a regression back to `/api/**` (or to a wildcard) silently re-opens `/api/data` to
  browser pages and every test still passes. The existing preflight test would pass even if the CORS
  mapping were removed entirely (an unmapped OPTIONS returns 403/404, which is also `!= 401`).
- Smallest fix: add a test that an `OPTIONS` preflight with an `Origin` to `/api/data` returns 403 and
  carries no `Access-Control-Allow-Origin`, and tighten the `/api/query` preflight test to assert the
  `Access-Control-Allow-Origin: *` header.
- Verdict: `fix`.

### 4. "Omitted columns keep their defaults" is untested
- Task/design require it (`docs/tasks/2026-10-31-write-endpoint.md:46-47`); `DataWriteController.java:348-353`
  implements it by copying only the input columns into the `COPY` column list.
- Concrete failure: no test loads a *subset* of columns into a pre-existing table and checks that the
  omitted column got its `DEFAULT`. `csvLoadIntoTypedTableKeepsTypes` always supplies every column. A
  regression in the column-list construction (e.g. reordering, or dropping the column list) would still
  pass the suite.
- Smallest fix: add a test with `CREATE TABLE t (a text, b text DEFAULT 'x')` and a one-column CSV that
  asserts `b` becomes `'x'`.
- Verdict: `fix`.

### 5. Auto-created table + function that raises is untested
- `DataWriteController.java:175-179` drops the auto-created table only after a successful routine; a raise
  relies on the `finally { rollback() }` to remove it.
- Concrete failure: `functionThatRaisesRollsBackTheLoad` (`DataWriteApiTest.java:169-184`) uses a
  *pre-created* table (`boom_rows`), so `created == false` and the "auto-created table removed by rollback
  when the function raises" path is never exercised. Only the mid-COPY error case is covered
  (`unknownJsonKeyIsBadRequest`).
- Smallest fix: add a test that posts to a non-existent table with a raising function and asserts the table
  does not exist afterwards.
- Verdict: `fix`.

### 6. `RETURNS void` function success returns `[{"fn": null}]`, not `null`
- `DataWriteController.java:406-408` (`SELECT * FROM fn()` then `readRows`). `SELECT * FROM void_fn()`
  yields one row with a void-typed column; `getObject` maps void to `null`, so `result` becomes
  `[{"fn": null}]` rather than `null` (the value documented for "no rows / procedure").
- Concrete failure: cosmetic only; a void function (a common "do work, return nothing" shape) produces a
  surprising `result` shape, and it is untested.
- Smallest fix: treat a void-typed single column as no result, or leave it (document).
- Verdict: `not worth fixing` — same `getObject` semantics as the existing `memory` tool; no data is
  wrong, only the shape.

### 7. A routine with default arguments is reported as "Function not found"
- `DataWriteController.java:415` filters `p.pronargs = 0`, so `f(x int DEFAULT 1)` (callable as `f()`)
  matches nothing and `:419-421` returns "Function not found".
- Concrete failure: the error message is misleading ("not found" when it exists but takes an argument),
  but the behaviour (400) is correct per the "no-argument" contract.
- Verdict: `not worth fixing` — a wording nit; the test `functionNotFoundOrTakingArgumentsIsBadRequest`
  only asserts the status.

### 8. Deeply-nested JSON input is rejected at Jackson's default depth limit
- `DataWriteController.java:253` (`parser.readValueAsTree()`).
- Concrete failure: an input value nested deeper than the default `StreamReadConstraints` depth (1000)
  throws `StreamConstraintsException` (a `JacksonException`) and is mapped to `400` (`:90`), not `500`; a
  single very large object is materialized in memory. Both are bounded and non-fatal; "no size limits" is
  the owner's explicit decision.
- Verdict: `not worth fixing`.

### 9. Spurious `rollback()` after a successful `commit()` emits a warning
- `DataWriteController.java:181` then `:189-191`. The unconditional `finally { rollback() }` after a
  committed transaction sends a `ROLLBACK` that PostgreSQL answers with "no transaction in progress".
- Concrete failure: none — the warning is discarded by `clearWarnings()` (`:168`) before the next COPY on
  the pooled connection, and it matches the existing `ReadOnlyQueryController` pattern. One wasted
  round-trip per request.
- Verdict: `not worth fixing`.

## Checked, no defect found

- **Credential routing** (`TokenAuthFilter.java:51-58`, `AuthService.java`): three-way switch with no
  fall-through; the raw UUID, RO and RW each reach exactly one endpoint and are `401` elsewhere. Comparison
  is `MessageDigest.isEqual` (constant-time). A token matching nothing and a token of the wrong role are
  both `401 "Invalid token"` — indistinguishable, which is the safe choice, not a leak.
- **SQL safety** (`DataWriteController.java:472-496`): table/function names are matched with
  `Matcher.matches()` against `NAME` (implicit full-match, so `t; drop ...` fails) *and* quoted via
  `quote()`; input column names are only ever passed through `quote()` (inner `"` doubled). No path
  concatenates an unvalidated/unquoted identifier. `ensureTable`/`tableColumns` use `?` bind parameters.
- **JSON streaming** (`DataWriteController.java:113-134`, `:210-229`, `:245-259`): the array is parsed
  object-by-object; only the first object and one row at a time are held, so memory is O(largest row), not
  O(body). `null` vs empty-string vs nested object/array and embedded commas/quotes/newlines are emitted
  correctly to CSV (`csvRow`/`quoteCsv`); missing keys are `NULL`. Column order is matched by name, not
  position.
- **CORS change** (`WebConfig.java:29`): `/api/query` remains on the open mapping, `/api/data` is excluded;
  the write token is only reachable via a non-simple header (`Authorization`/`X-Access-Token`), which a
  browser page cannot send without a blocked preflight.

## Must fix before shipping

1. `rows_ignored`/`rows_received` must not silently read `0` on non-English `lc_messages` — count JSON
   objects (free) and, for CSV, count records or fail loud when the NOTICE is unparseable (finding 1).
2. Document that a called routine must not `COMMIT`/`ROLLBACK`, or the atomicity guarantee is silently
   void for procedures (finding 2).
3. Add the missing tests: CORS exclusion of `/api/data`, omitted-column defaults, and auto-created table
   rollback on a raising function (findings 3, 4, 5).
