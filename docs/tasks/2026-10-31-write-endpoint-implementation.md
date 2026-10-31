# Implementation report: bulk write endpoint (`POST /api/data`)

Date: 2026-10-31
Task: `docs/tasks/2026-10-31-write-endpoint.md`
Design: `docs/superpowers/specs/2026-10-31-write-endpoint-design.md`
Fix round: `docs/tasks/2026-10-31-write-endpoint-fixes-01.md` (items 1-9 applied)

Status: implemented, full suite green, not committed.

## What changed per file

### Main code

- `config/MemoryConfig.java` - added `saltRo` / `saltRw` and an explicit all-args constructor (constructor
  binding) that refuses to start when either salt is null/blank or when they are equal. The check lives in
  the constructor, so a bad `config.yml` fails the Spring context instead of failing at request time.
- `connections/AuthService.java` - added `Role { AGENT, READ, WRITE }`; `resolve(token, role)` compares the
  presented token against the raw UUID, `readToken(agent)` or `writeToken(agent)`. The derived tokens are
  `SHA256(lowercase uuid + ":" + salt)` in hex, computed in one place (`deriveToken`). The unsalted
  `resolveByTokenHash` is gone; `resolve(String)` is replaced by the role-aware one.
- `auth/TokenAuthFilter.java` - explicit three-way routing on the routed path, no fall-through:
  `/api/query` -> `READ`, `/api/data` -> `WRITE`, everything else (the MCP endpoint) -> `AGENT`. A credential
  of the wrong role is `401`. The `routedPath()` hardening (dot segments, percent encoding) is untouched.
- `api/DataWriteController.java` - the whole flow: content-type detection, header validation, CSV header
  parsing (PostgreSQL CSV dialect), streaming JSON array -> CSV, table creation, `COPY ... FROM STDIN WITH
  (FORMAT csv, HEADER true, ON_ERROR ignore)`, optional function/procedure call, auto-created table drop,
  commit/rollback, response and error mapping. Round 1 reworked the CSV record accounting, the CSV state
  machine, the session GUCs, the routine lookup, the column guards and the drop (details below).
- `api/SqlErrors.java` - the connection-failure classifier (SQLState class `08`, `53`, `57P01`, `57P03`)
  extracted from `ReadOnlyQueryController`; both controllers use it now.
- `api/ReadOnlyQueryController.java` - only the classifier moved out; the read behaviour is unchanged.
- `config/WebConfig.java` - the open CORS mapping is narrowed from `/api/**` to `/api/query`; `/api/data` is
  not callable from a browser page.
- `tools/MemoryMcpServerTools.java` - `get_connection_info` returns `query_endpoint`, `query_token`,
  `write_endpoint`, `write_token`; the tool description now explains both endpoints and that the write token
  must never go into a page.
- `src/main/resources/application.yml` - kept the pre-existing uncommitted "Schemas: organize the knowledge"
  block and extended the instructions with the two endpoints, the two tokens, the rule that RW is shell-only,
  and the request/response shape of the write endpoint.
- `README.md` - credential matrix, write-endpoint protocol, response and error table, CORS note, salt
  configuration, exact token derivation and `sha256sum` snippets. Round 1 added the `X-Access-Token` variant,
  the header-only CSV rule, the empty/missing header wording, the function-schema and `RETURNS void` shape,
  the `/api/`-paths-answer-401 rule and an upgrade note for existing deployments.

### Tests

- `BaseApplicationTest.java` - plants `salt-ro` / `salt-rw` in the generated `config.yml`, adds `SALT_RO`,
  `SALT_RW`, `dataUrl()`, `roToken()` and `rwToken()` helpers (the test computes the hashes itself, so the
  derivation is exercised independently of `AuthService`). The old comment about the SHA-256 of the UUID is
  updated.
- `tests/rest/ReadOnlyQueryApiTest.java` - uses the salted read token; adds "write token is rejected on
  `/api/query`". All read-only assertions are unchanged.
- `tests/tools/MemoryToolTest.java` - `get_connection_info` assertions moved to the new keys and salted
  tokens.
- `tests/AuthFilterTest.java` - dot-segment tests now use the read token; new test that both derived tokens
  are rejected on `/mcp`.
- `tests/rest/DataWriteApiTest.java` - 37 integration tests (list below).
- `tests/config/MemoryConfigTest.java` - new, `ApplicationContextRunner` tests that the context starts with two
  different salts and fails when a salt is missing, empty, or equal.

## Round 1 fixes (items 1-9)

1. **`rows_ignored` no longer depends on the NOTICE.** The `lc_messages`/`is_superuser` code and the
   warning-chain reading are deleted. The CSV data stream is wrapped in `CsvRecordCounter`, a
   `FilterInputStream` that counts records with the same dialect rule the header parser uses; JSON counts the
   objects it emits. `rows_received` is that count, `rows_written` stays `CopyIn.endCopy()` (the `COPY N`
   tag), and `rows_ignored = rows_received - rows_written`. Tests now skip two or more rows in CSV and in
   JSON.
2. **CSV state machine rewritten** as one byte-level parser for the header (raw bytes + column values) and an
   equivalent counter for the data stream: `""` is a literal quote, a single quote closes a quoted section,
   the characters after it are ordinary data again. CRLF is consumed as one record terminator.
   *Deviation from the item's wording:* the item says a quote is special only at field start and that
   `a,b"c,d` is three columns. That is not what PostgreSQL 18.6 does — verified directly against the
   `postgres:18-alpine` image:
   - `COPY t (a,b,c) ... 'a,b"c",d'` inserts `a | bc | d` — the quote opened a quoted section mid-field and
     the quoted content was joined into the field;
   - `'a,b"c,d'` (unterminated) opens a quote at `b"` and swallows the rest of the input ("unterminated CSV
     quoted field" with `HEADER false`; with `HEADER true` the whole file is skipped as the header record).

   Implementing "quote only at field start" would therefore make the server disagree with the COPY it
   drives: it would build three columns while PostgreSQL consumes the data row as part of the header. The
   parser follows the measured PostgreSQL rule instead. The tests pin it: `a,b"c",d` loads as columns
   `a`/`bc`/`d`, `"b""c",d` loads as `b"c`/`d`, and a quoted newline in the header proves that `HEADER true`
   skips the whole logical record.
3. **Routine lookup** now filters `p.prokind IN ('f','p')`. When nothing matches, a second check looks for a
   zero-argument aggregate/window function of that name and answers `400 "Not a callable function"` instead
   of the generic "not found". Tested with `pg_catalog.count`.
4. **Session GUCs are pinned** inside the write transaction: `client_encoding = 'UTF8'`,
   `datestyle = 'ISO, YMD'`, `intervalstyle = 'postgres'`. Test: the pool is poisoned with
   `SET datestyle = 'german'` through `memory`, then `24/01/31` is loaded into a `date` column — only under
   `ISO, YMD` is it `2024-01-31` (German reads `2031-01-24`, `MDY` rejects it), and the test asserts the
   stored value.
5. **Drop tolerates a missing table**: `DROP TABLE IF EXISTS`.
6. **Column guards**: an empty column name and a name longer than 63 bytes (UTF-8) are `400` before the table
   is touched. Tested for CSV (empty cell, blank header line) and JSON (64-byte key).
7. **CORS tests**: a preflight to `/api/query` must return `Access-Control-Allow-Origin`, a preflight to
   `/api/data` must not.
8. **Two missing behaviours tested**: a table column omitted from the input keeps its `DEFAULT`, and a
   function that raises leaves no rows and no auto-created table behind.
9. **Documentation**: the contract (`docs/tasks/2026-10-31-write-endpoint.md`) and `README.md` now say that a
   header-only CSV is zero data rows (still `400`), that "missing header" means `X-Target-Table` only, that a
   function resolves in its own schema (unqualified -> `public`), what `RETURNS void` returns, that
   `/api/data` accepts `X-Access-Token`, that other `/api/` paths answer `401`, and the upgrade order for
   the salts with their permanence.

## Round 3 fixes (Windows/Excel inputs)

1. **`CsvRecordCounter` counted CRLF twice.** It incremented on `\r` and again on `\n`, so a Windows-style
   body reported `rows_received = 2 × actual` and a phantom `rows_ignored = actual`. The counter now keeps a
   `pendingCr` flag, as the header reader does: a `\r\n` pair counts once, a lone `\r` still ends a record, and
   a `\n` that follows a `\r` is not treated as a new record (it is also not added to `inRecord`, so a file
   ending in `\r\n` reports no phantom final record). Test: a CRLF body with one bad row asserts exactly
   `rows_received 3`, `rows_written 2`, `rows_ignored 1` and two stored rows.
2. **A leading UTF-8 BOM is stripped from the CSV body.** Excel writes `EF BB BF` before the first column
   name; previously it became part of the column name, so a load into a pre-created table failed with
   "unknown column" and an auto-created table got an invisible prefix. `skipUtf8Bom` peeks the first three
   bytes (the pushback buffer is now 3 bytes) and drops them only when they are exactly the UTF-8 BOM;
   anything else is pushed back unchanged. It runs before the header is parsed, so the header bytes replayed
   into the COPY are the same BOM-free bytes the columns were parsed from — PostgreSQL skips that record via
   `HEADER true`, so the two stay consistent by construction. Test: a body starting with the BOM loads into a
   pre-created typed table and the value lands.

## How `rows_received` / `rows_ignored` are obtained (rounds 1 and 3)

`rows_written` is `CopyIn.endCopy()`, the `COPY N` command tag. `rows_received` is counted by the server while
the bytes stream to the COPY:

- CSV: `CsvRecordCounter` counts record terminators outside quoted sections, plus a final record without a
  terminator. It implements the same rule as the header parser, so quoted commas, quotes and newlines do not
  end a record. `\r\n` is one terminator (a `pendingCr` flag suppresses the second count), a lone `\r` or `\n`
  is one. The header record is consumed by the parser before the counter starts, so it is not counted.
- JSON: the number of objects the server serialises (the first object plus each array element).

`rows_ignored` is `rows_received - rows_written`. Nothing about the count depends on PostgreSQL log messages
or on the role's privileges, which removes the previous NOTICE/`lc_messages` path entirely.

## Decisions taken where the task was silent

1. An unqualified `X-Function-Name` resolves to `public` (the same rule as the table), **not** to the target
   table's schema - "the same rule applies to the function name" in the task. A schema-qualified function is
   called in that schema; the table's schema is only used for the table and for the drop.
2. A CSV with a header but no data bytes is `400` ("The CSV body has no data rows"); the task lists
   "header-only CSV" as a zero-columns error, and a header with no rows would otherwise be a silent success.
3. The first line of a CSV body is always the header, so a missing CSV header is not detectable: an empty body
   answers `400` ("The CSV body is empty: no columns") and a blank first line answers `400` through the
   empty-column-name guard.
4. JSON array elements that are not objects (scalars, arrays, `null`, or a first element that is
   `[]`/`{}`) are `400`. Duplicate keys in **later** JSON objects are also `400`, not only in the first one.
5. A procedure call returns `result: null`; only a function's result rows are collected. A function that
   returns a set becomes a list of row objects keyed by column label; a `RETURNS void` function returns one
   row with a null value keyed by the function name (PostgreSQL's `SELECT * FROM f()` shape).
6. Error mapping: `BadRequest` (validation) -> `400`; any `SQLException` -> `400` (the database rejected the
   statement or the function raised); any `JacksonException` -> `400`; connection failures -> `503`; anything
   else -> `500`. Every load failure is non-2xx and the transaction rolls back.
7. The CSV header is read byte-wise from the request stream (not buffered), the raw header bytes are replayed
   into the COPY through a `SequenceInputStream`, so PostgreSQL itself skips the record via `HEADER true` and
   the server never re-emits CSV bytes.
8. The filter's default branch is `AGENT` for every path it guards that is not `/api/query`/`/api/data`,
   matching the task's "everything else (the MCP endpoint) accepts only the UUID". Guards on `/mcp/*` are
   unchanged.
9. An existing object with the name of the target that is not a table (a view, for example) is treated as
   missing; `CREATE TABLE` then fails and the request answers `400` with the PostgreSQL message.
10. The GUCs are pinned with `SET LOCAL` (the values the server itself emits), not by rejecting a request when
    the session differs, so a leftover value from `memory` is simply overridden for the load.
11. The UTF-8 BOM is removed from the stream before the header is parsed, so the replayed header bytes and the
    parsed columns are the same bytes (PostgreSQL skips that record anyway). It is only removed at the very
    start of the CSV body and only for the exact three-byte sequence; a BOM anywhere else is ordinary data.

## What was not done, and why

- No automated test for an **ambiguous** function. PostgreSQL's uniqueness is on name + argument types, so two
  zero-argument routines cannot coexist in one schema; the `> 1 pg_proc row` branch exists but cannot be
  triggered from a real database. "Not found", "takes arguments", and "aggregate instead of function" are
  tested.
- No automated test for `503`: it needs the database to disappear mid-test; the classifier itself is the
  existing, unchanged one, now shared.
- Nothing from the task's "hard constraints" list: no size/row/rate limits, no idempotency key, no audit
  table, no upsert, no NDJSON, no extra headers, no temporary tables, no staging schema, no server-side file
  fetch, no schema semantics.
- No change to the `memory` tool or the read endpoint beyond which credential they accept and the shared
  classifier.
- No commit, no push.

## Test counts (last run)

```
./mvnw test

AuthFilterTest        10 tests, 0 failures, 0 errors, 0 skipped
MemoryConfigTest       5 tests, 0 failures, 0 errors, 0 skipped
DataWriteApiTest      40 tests, 0 failures, 0 errors, 0 skipped
ReadOnlyQueryApiTest  10 tests, 0 failures, 0 errors, 0 skipped
MemoryToolTest         7 tests, 0 failures, 0 errors, 0 skipped

Tests run: 72, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

### `DataWriteApiTest` cases

CSV into a typed table (values keep the table's types, `rows_*` counts); CSV into a missing table (`text`
columns, rows land); JSON array (columns from the first object, nested value stored as JSON text, missing key
`NULL`, comma inside a value survives); conversion failures skipped and counted for CSV (6 rows: 3 written,
3 ignored) and for JSON (4 rows: 2 written, 2 ignored); a quoted newline in a data cell counts as one record;
a CRLF body with one skipped row counts exactly 3 received / 2 written / 1 ignored (no CRLF double count); a
leading UTF-8 BOM is stripped so the load lands in a pre-created typed table;
function receives the rows and its result comes back;
function in another schema; procedure called with `result: null`; read token and raw UUID rejected; invalid
table name; invalid function name; function that raises rolls back; function that raises leaves no
auto-created table; auto-created table dropped after a successful function and kept without one; a missing
input column keeps its `DEFAULT`; unqualified target lands in `public`; reserved-word table `order`; quoted
CSV header cell with a comma; a mid-field quote (`a,b"c",d` -> `a`/`bc`/`d`); a doubled quote (`"b""c"` ->
`b"c`); a quoted newline in the header; ragged CSV row `400` with nothing landed; unknown column `400`; zero
columns (empty body, header-only, `[]`, `{}`, `[{}]`); empty column name and 64-byte column name `400`; bare
JSON object `400`; duplicate CSV/JSON columns `400`; unknown JSON key `400`; function not found / taking
arguments `400`; an aggregate is "not a callable function" `400`; `text/csv; charset=utf-8`; unsupported
content type; `X-Access-Token` accepted; missing token `401`; CORS open on `/api/query` and closed on
`/api/data`; a German `datestyle` left on a pooled connection does not change date parsing.
