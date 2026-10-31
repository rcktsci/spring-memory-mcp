# Review: 2026-10-31 write endpoint

Reviewed against the existing server (`TokenAuthFilter`, `AuthService`, `MemoryConfig`,
`WebConfig`, `ReadOnlyQueryController`, `MemoryMcpServerTools`), the test fixtures
(`BaseApplicationTest`, `ReadOnlyQueryApiTest`, `MemoryToolTest`, `AuthFilterTest`), and
PostgreSQL 18 behaviour verified from the official COPY docs and the REL_18_STABLE source
(`copyfrom.c`, `copyfromparse.c`) plus pgjdbc `CopyManager`/`QueryExecutorBase`.

Note: the `websearch` tool returned HTTP 403 for every query, so PostgreSQL/pgjdbc facts were
verified via `webfetch` of postgresql.org and raw github sources instead.

## Findings

### 1. Required salts break every existing test at startup, and the task does not list fixing the fixtures
- `docs/tasks/2026-10-31-write-endpoint.md:65` ("required - the server refuses to start without them"); `src/test/java/.../BaseApplicationTest.java:48-68` plants a `config.yml` with only `public-url` + `agents`.
- Failure: `MemoryConfig` gains required `saltRo`/`saltRw`; `BaseApplicationTest`'s config has neither, so the Spring context fails to boot and **every** test fails before the new code is even exercised. `BaseApplicationTest.java` is absent from "Files to touch" (task:100-108) and the "Tests" list (task:113-125).
- Fix: add `saltRo`/`saltRw` values to the planted `config.yml` in `BaseApplicationTest`, and list `BaseApplicationTest.java` as a touched file.

### 2. Existing tests hard-code the unsalted read token and the old `get_connection_info` shape; they will fail and are not listed for update
- `src/test/java/.../ReadOnlyQueryApiTest.java:29,46,54,61,70,81,88` send `sha256Hex(ALPHA_TOKEN)` (unsalted) to `/api/query` and expect `200`; `MemoryToolTest.java:65-72` asserts `get_connection_info` returns `{endpoint, authentication}` with `authentication == sha256Hex(ALPHA_TOKEN)`.
- Failure: the design replaces the read credential with `SHA256(uuid + ":" + saltRo)` (design:31-46, task:58-69), so `/api/query` no longer accepts `sha256Hex(uuid)`. Every `ReadOnlyQueryApiTest` case returns `401` instead of `200`; `getConnectionInfoReturnsEndpointAndSha256OfToken` fails on both shape and value. Task:133-134 says `/api/query` behaviour "must not change apart from which credential they accept", but never lists updating these tests.
- Fix: update the tests to derive the salted RO token, and specify the new `get_connection_info` keys (see finding 11).

### 3. `rows_ignored` must be recovered from a translatable, human-readable NOTICE; there is no structured count, and its capture from pgjdbc COPY is unverified
- `docs/tasks/2026-10-31-write-endpoint.md:48-49`, `docs/superpowers/specs/2026-10-31-write-endpoint-design.md:49` say "PostgreSQL reports the count in a NOTICE".
- Verified behaviour (PG 18 `copyfrom.c`): a single summary NOTICE is emitted at the *end* of `COPY FROM`, and only when `num_errors > 0`:
  `errmsg_plural("%" PRIu64 " row was skipped due to data type incompatibility", ...)`.
  It is `errmsg_plural`, i.e. **translatable**; a non-English `lc_messages` changes the sentence. The only structured signal is the command tag `COPY N` (`rows_written`), returned by pgjdbc `CopyIn.endCopy()`.
- Failure: the implementer will look for a clean number; there is none. They must (a) parse a leading integer out of localized text, (b) treat "no NOTICE" as `rows_ignored == 0`, and (c) confirm pgjdbc actually surfaces this NOTICE during COPY — pgjdbc routes server notices through `QueryExecutorBase.addWarning`/`getWarnings()`, but the `startCopy`/`endCopy` path is not guaranteed to attach it, and `CopyManager.copyIn(sql)` gives no warning handle.
- Fix: pin the session with `SET LOCAL lc_messages = 'C'` before the COPY, extract the leading `%PRIu64`; add a fallback `rows_ignored = rows_received - rows_written` for the JSON path, and state explicitly in the spec that `rows_written` = `COPY N` tag and `rows_received = rows_written + rows_ignored` for CSV (this holds only because `ON_ERROR ignore` skips *only* conversion errors — see finding 4). Verify pgjdbc surface during implementation.

### 4. Ragged CSV rows abort the whole load; `ON_ERROR ignore` does not skip them
- `docs/tasks/2026-10-31-write-endpoint.md:48-49` ("rows that fail conversion are discarded").
- Verified (`copyfromparse.c` `CopyFromTextLikeOneRow`): a row with more fields than the column list raises a **hard** error `"extra data after last expected column"`; fewer fields raises `"missing data for column ..."`. These are not soft input-conversion errors, so `ON_ERROR ignore` does not catch them; the entire COPY fails and the transaction rolls back.
- Failure: an implementer reading "rows that fail conversion are discarded" will assume any bad row is skipped and counted. One ragged row silently kills the whole load instead (a `400` with the raw message, nothing landed). The spec never defines ragged-row behaviour and the tests don't cover it.
- Fix: document that a column-count mismatch is a hard `400`/rollback (or pre-validate the CSV column count), and add a test.

### 5. The procedure path breaks the "one transaction" invariant
- `docs/tasks/2026-10-31-write-endpoint.md:50-53`, design:96-101 ("call it in the same transaction", `CALL proc()`).
- Failure: a PostgreSQL **procedure** may issue `COMMIT`/`ROLLBACK` internally; a **function** may not. If the agent's procedure commits, the COPY'd rows and the auto-created table are committed *before* the server's step "drop the table if auto-created" and its own commit — so the auto-created table is left behind and the "nothing lands on failure" guarantee is broken. The design asserts "same transaction" without noting this.
- Fix: restrict the feature to functions only (`prokind = 'f'`), or document that a called procedure must not commit and that the invariant is the caller's to preserve.

### 6. `saltRo == saltRw` collapses the three credentials into two
- `docs/tasks/2026-10-31-write-endpoint.md:65` requires both salts but never requires them to differ.
- Failure: if an operator sets the same value for both (or both empty), `RO == RW`, and the RO token published in user pages also unlocks `/api/data` — exactly the credential confusion the matrix (task:58-62, design:31-35) is meant to prevent.
- Fix: validate at startup that both salts are non-empty and `saltRo != saltRw`; refuse to start otherwise.

### 7. Three-way auth routing is underspecified and can fall through to the wrong credential
- `src/main/java/.../TokenAuthFilter.java:52-54` currently branches only on `routedPath().startsWith("/api/")` (hash vs raw UUID). Task:102 says `/mcp`→UUID, `/api/query`→RO, `/api/data`→RW.
- Failure: a two-way "`/api/*` → hash" mapping cannot distinguish RO from RW; and if the filter falls through to `resolve(token)` for any non-`/api` path, the raw UUID would open `/api/data` (the matrix forbids it). The existing `AuthService.resolveByTokenHash` (`AuthService.java:37-48`) hashes the *unsalted* UUID, so it must be replaced, not kept as the `/api/query` resolver.
- Fix: in the filter, branch explicitly on the routed path: `/api/query`→RO resolver, `/api/data`→RW resolver, everything else→UUID resolver; return `401` when the presented token does not match the *endpoint-specific* credential (no fall-through). Keep the existing `routedPath()` dot-segment/encoding hardening.

### 8. Function/procedure call and COPY target must be schema-qualified; the design's own example violates its search_path rule
- `docs/superpowers/specs/2026-10-31-write-endpoint-design.md:88-89` ("never use session `search_path`"), but design:98/52 uses unqualified `SELECT * FROM fn()` / `CALL proc()`.
- Failure: the `memory` tool runs arbitrary SQL (`MemoryMcpServerTools.java:66-94`), including `SET search_path`, on pooled connections; HikariCP reuses them. An unqualified `fn()` or `table` in the write path can be redirected by leftover connection state. The table side is stated as resolved-to-public, but the function call and the emitted `COPY`/`CREATE` SQL must actually be qualified, which the spec only implies.
- Fix: always emit `"schema"."table"` and `"schema"."fn"`/`"schema"."proc"` (resolve unqualified → `public`); never emit a bare identifier.

### 9. Table/function names are validated but not quoted, so reserved words break
- `docs/tasks/2026-10-31-write-endpoint.md:89-91`, design:74-78 validate header names but only require *column* names to be quoted.
- Failure: the regex `^[a-z_][a-z0-9_]*(\.[a-z_][a-z0-9_]*)?$` prevents injection but admits SQL keywords (`order`, `user`, `table`, `from`, `select`). A table or function named `order` (very common) produces `COPY order (...) FROM STDIN ...` / `SELECT * FROM order()` syntax errors → `400`/`500`, though the agent can legitimately create such a table via `memory`.
- Fix: quote schema/table/function identifiers exactly like the column names (`"` with inner `"` doubled); the regex already guarantees safety.

### 10. Function/procedure resolution is underspecified (overloads, zero-arg, `prokind`)
- `docs/tasks/2026-10-31-write-endpoint.md:52`, design:52 ("look up `pg_proc.prokind`", "`SELECT * FROM fn()`").
- Failure: a name can be overloaded (multiple `pg_proc` rows for the same `proname`+namespace), and `SELECT * FROM fn()`/`CALL proc()` assume a zero-argument routine. The spec never says the routine must be zero-arg and uniquely named; an overloaded or non-zero-arg routine is undefined, and "function not found / ambiguous" is absent from the error table (design:140).
- Fix: resolve `prokind IN ('f','p') AND pronargs = 0` in the resolved schema; require exactly one match, else `400`; document "the function/procedure must take no arguments".

### 11. `get_connection_info` response shape (key names) is unspecified
- `docs/tasks/2026-10-31-write-endpoint.md:69`, design:38/166: "returns both derived tokens and both endpoints" — no key names.
- Failure: the implementer invents keys, and README + server instructions (task:106-108) must match. The existing test (`MemoryToolTest.java:65-72`) pins `endpoint`/`authentication`, which is now wrong. Downstream consumers (operator tooling, the agent) depend on these names.
- Fix: name the fields (e.g. `read_token`, `write_token`, `query_endpoint`, `write_endpoint`) in the task, and update the existing test and README to them.

### 12. CSV header parsing is in tension with "bytes go untouched", and the dialect is unspecified
- `docs/superpowers/specs/2026-10-31-write-endpoint-design.md:112-113` ("CSV bytes go to COPY untouched ... the server does not re-implement RFC 4180 quoting") vs design:85-86/task:42-43 ("derive the columns ... CSV - the header row") and task:48 (the `COPY` column list).
- Failure: the server *must* parse the header line to build the `COPY` column list and the auto-`CREATE TABLE`. If it splits on commas naively, a header cell containing a comma/newline (`a,"b\nc"`) yields the wrong column names, which then disagree with the data PostgreSQL parses. The design gives no CSV dialect parameters (delimiter/quote/escape), so "the same as PostgreSQL defaults" is left implicit.
- Fix: state the header is parsed with PostgreSQL's default CSV dialect (comma, `"`, escape = quote), or read the header with a real CSV parser; keep passing the full body bytes to `COPY` with `HEADER true` (PG 18 `HEADER true` discards line 1 without name-matching — verified).

### 13. Zero-column and duplicate-column inputs are unhandled
- `docs/tasks/2026-10-31-write-endpoint.md:42-43` (columns from first object / header), task:45 (`CREATE TABLE ... with the input columns`).
- Failure: an empty JSON array `[]` or a first object `{}` produces zero columns → `CREATE TABLE t ()` is invalid and `COPY t () FROM STDIN` is invalid; duplicate column names (duplicate CSV header, or duplicate JSON keys) produce `CREATE TABLE t ("x" text, "x" text)` → error. Neither is mapped in the error table (design:135-142).
- Fix: define these as `400` ("no columns" / "duplicate column"), and add tests.

### 14. Error mapping is incomplete and the 503 classifier is not shared
- `docs/superpowers/specs/2026-10-31-write-endpoint-design.md:135-142` lists errors, but omits: function/procedure not found or overloaded; zero/duplicate columns (finding 13); bare JSON object body (not an array); header-only CSV with zero data rows; a `Content-Type` carrying parameters (`text/csv; charset=utf-8`) which a naive string match rejects as `400`.
- Failure: the implementer must guess status codes for these, and will likely reuse `ReadOnlyQueryController.isConnectionFailure` (`ReadOnlyQueryController.java:83-97`), which is `private` and not mentioned in "Files to touch".
- Fix: extend the error table, and specify that the connection-failure classification (SQLState `08`/`53`/`57P01`/`57P03`) is shared (extract a common helper or duplicate it).

## What would block implementation

1. `BaseApplicationTest`'s `config.yml` lacks the required salts → the whole Spring context fails to boot, so no test runs (finding 1).
2. `ReadOnlyQueryApiTest` and `MemoryToolTest` pin the old unsalted read token and old `get_connection_info` shape → they fail once the credential model changes (finding 2).
3. No structured way to obtain `rows_ignored`/`rows_received` for CSV: the count lives in a translatable NOTICE whose surfacing through pgjdbc COPY is unverified, and `rows_received` is only derivable as `rows_written + rows_ignored` (findings 3, 4).
4. Procedure/fn invocation semantics are open: schema-qualification against leaked `search_path`, zero-arg/overload resolution, and the transaction-breaking `CALL` (findings 8, 10, 5).
