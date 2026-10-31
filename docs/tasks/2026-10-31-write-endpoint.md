# Task: bulk write endpoint for the memory MCP

Date: 2026-10-31
Status: ready for development (design approved by the owner; reviewer notes appended at the end)

## What to read first

- The design: `docs/superpowers/specs/2026-10-31-write-endpoint-design.md` - it is the contract. Read it fully.
- The existing server: `src/main/java/se/rocketscien/mcp/springmemorymcpserver/`
  (`auth/TokenAuthFilter.java`, `connections/AuthService.java`, `connections/AgentConnectionsManager.java`,
  `api/ReadOnlyQueryController.java`, `tools/MemoryMcpServerTools.java`, `config/MemoryConfig.java`,
  `config/WebConfig.java`) and the tests in `src/test/java/`.
- Stack: Spring Boot 4.1.2, Spring AI 2.0.2, Java 25, PostgreSQL 18, pgjdbc, HikariCP. Jackson comes with Spring Boot.

## Goal

An agent must be able to load bulk data into its own PostgreSQL database **without the data passing through the
model context window**. Today the only write path is the `memory` tool (arbitrary SQL), so every byte of a bulk
load crosses the LLM context. Add a second write path: the agent (or a script it runs) streams a file into a
dedicated endpoint, and the server puts it into the database.

## The contract in short

```
POST /api/data
Authorization: Bearer <RW token>     # or X-Access-Token: <RW token>
Content-Type: text/csv | application/json
X-Target-Table: [schema.]table        # where the data goes
X-Function-Name: [schema.]function     # optional: called right after the load

<body> = CSV with a header row, or a JSON array of objects
```

Success answer: `{"rows_received": N, "rows_ignored": N, "rows_written": N, "result": ...}`.
Errors: 401 (bad token, or a path under `/api/` that is not exactly `/api/query` or `/api/data`), 400
(invalid name, missing `X-Target-Table`, unsupported content type, empty body, unknown column, zero or
duplicate columns, a CSV with a header but no data rows, ragged CSV row, function not callable / not
found or not unique, function raised), 503 (database unreachable), 500 (unexpected). Body shape
`{"error": "..."}`. A failed load is always a non-2xx answer.

Counting the rows:
- `rows_written` is the `COPY N` command tag - a structured value, always available.
- `rows_received` is counted from the input as the server streams it: CSV by counting records with
  PostgreSQL's dialect rule (a record ends at an unquoted line terminator; quoted commas, quotes and
  newlines do not end it), JSON by counting the objects it emits. This never depends on log messages.
- `rows_ignored` = `rows_received` - `rows_written`: the rows PostgreSQL skipped on a type mismatch.
- `ON_ERROR ignore` skips **only** input-conversion errors. A row whose field count differs from the column
  list is a hard error - it aborts the whole COPY and the transaction rolls back -> `400`.

Server flow, one transaction:

1. Authenticate the RW token against `SHA256(lowercase uuid + ":" + saltRw)` for exactly one agent.
2. Derive the columns from the input: CSV - the header row; JSON - the keys of the first object (parsed as a
   stream, never fully in memory). A later JSON object with an unknown key is an error; a missing key is NULL.
   Zero columns (empty body, `[]`, `{}`) -> 400. A CSV with a header but zero data rows is also a `400`.
   Duplicate column names (repeated CSV header cell, repeated JSON key) -> 400. An empty column name and a
   column name longer than 63 bytes are `400`: PostgreSQL rejects a zero-length identifier and silently
   truncates a longer one.
   The CSV header must be parsed with PostgreSQL's default CSV dialect (comma delimiter, `"` quote, escape is
   the quote itself) - a header cell may itself contain a comma or a newline, so do not split on commas.
   The first line of a CSV body is always taken as the header, so a "missing CSV header" is not detectable;
   "missing header" in the error list is the `X-Target-Table` header.
3. Resolve the target: an unqualified name resolves to `public`. Never use the session `search_path` - the
   `memory` tool runs `SET search_path` on pooled connections, so a leftover value would redirect an
   unqualified name. The function name follows the same rule in **its own** schema: an unqualified function
   resolves to `public` independently of the target table's schema.
4. Ensure the table exists. Missing: create the schema if needed, then the table with the input columns, all
   type `text`. Existing: use as is. Input column that the table does not have -> 400. Table columns missing from
   the input keep their column default.
5. `COPY <target> (<columns from the input>) FROM STDIN WITH (FORMAT csv, HEADER true, ON_ERROR ignore)`.
   The header line is skipped by PostgreSQL (`HEADER true` discards line 1 without matching names), so the
   column list the server builds must be in the header's order.
6. If `X-Function-Name` is set: call it in the same transaction and the same session. It reads the data from the
   target table (the agent wrote that function, it knows the name). The routine is resolved in **its own**
   schema (unqualified -> `public`, independently of the target table's schema): it must take **no arguments**
   and match exactly one callable `pg_proc` row (`pronargs = 0`, `prokind IN ('f','p')`); zero matches, an
   ambiguous name, or an aggregate/window function of that name -> 400. A function is called as
   `SELECT * FROM <fn>()`, a procedure as `CALL <proc>()` (`pg_proc.prokind`); the result rows of a function
   go into `result` as a list of row objects. A `RETURNS void` function yields one row with a null value keyed
   by the function name; only a procedure's `result` is `null`.
   A procedure cannot break out of the transaction: PostgreSQL refuses `COMMIT` inside a procedure that is
   called inside an explicit transaction block ("invalid transaction termination"), and the server always runs
   inside one - so a procedure that tries to commit makes the whole load fail and roll back.
7. If the table was auto-created and the function ran successfully: drop the table (`DROP TABLE IF EXISTS`,
   because the function itself may have removed it).
8. Commit.

## Credential model (the server changes too)

| Credential | Derivation | Held by | `/mcp` | `/api/query` | `/api/data` |
|---|---|---|---|---|---|
| agent UUID | - | the MCP client (the harness), never the model | yes | no | no |
| RO | `SHA256(uuid + ":" + saltRo)` | the model; published in user pages | no | yes | no |
| RW | `SHA256(uuid + ":" + saltRw)` | the model, for shell use only | no | no | yes |

- The root UUID stays in the config and never leaves it.
- The two salts are **global**, one pair per deployment, in the config next to the agent list, **required** - the
  server refuses to start without them. There is no unsalted fallback.
- At startup the server validates that both salts are non-empty and that `saltRo != saltRw`; otherwise it
  refuses to start. Equal salts would make the published RO token unlock writes.
- The filter routes explicitly, with no fall-through: `/api/query` accepts only the RO token, `/api/data`
  accepts only the RW token, everything else (the MCP endpoint) accepts only the UUID. A token that does not
  match the credential of that exact endpoint is `401` - the raw UUID must never open `/api/data`, and the RO
  token must never open `/mcp`.
- The existing unsalted resolver (`AuthService.resolveByTokenHash`) is replaced by salted RO and RW
  resolvers; keep the existing `routedPath()` hardening (dot segments, percent encoding) as it is.
- Exact derivation: lowercase UUID, a colon, then the salt string exactly as written in the config; UTF-8 bytes;
  hex digest. Write this down in the README so operator tooling computes the same value.
- `get_connection_info` returns these keys: `query_endpoint`, `query_token` (RO), `write_endpoint`,
  `write_token` (RW). It never returns the UUID.
- The write endpoint must not be in the open CORS mapping (only `/api/query` keeps `allowedOrigins: *`).
- The server instructions must say: RW is for loading data from the shell and must never be embedded in a page;
  RO is for pages.

## Hard constraints (the owner decided these - do not add limits)

- **No size, row or rate limits.** The only backstop is the agent's own database.
- **No idempotency key.** A retried failed script may duplicate rows.
- **No audit table.** Nothing is written about the ingest anywhere.
- **No upsert operation.** A function that does `MERGE` covers it.
- **No NDJSON input.** CSV and JSON only.
- **No `X-Data-Columns` / `X-Data-Format` headers.** Columns come from the input, format from `Content-Type`.
- **No temporary tables, no staging schema.** Regular tables only: a session-scoped table is invisible to other
  pooled connections.
- **No schema semantics.** A schema is just part of the table name.
- **No server-side file fetch** by path or URL. The stream arrives as the request body.

## Safety rules

- Table and function names from the headers are validated against `^[a-z_][a-z0-9_]*(\.[a-z_][a-z0-9_]*)?$`;
  anything else is 400. Never concatenate an unvalidated name into SQL.
- The validated table and function names are still **quoted** as identifiers (`"` with inner `"` doubled) and
  always **schema-qualified** - a name like `order` is a legal table name and must not become a syntax error.
- Column names from the input are **quoted** as identifiers (`"` with inner `"` doubled), so any name is safe.
- CSV bytes go to `COPY` untouched - PostgreSQL owns the CSV dialect (unquoted empty field is NULL, quoted empty
  field is an empty string). A JSON array is streamed by the server and re-emitted as CSV rows with RFC 4180
  quoting; JSON `null` becomes an unquoted empty field (SQL NULL), an empty JSON string becomes a quoted empty
  field, a nested object or array becomes its JSON text.
- One database per agent: a write can only ever touch the writing agent's own database.
- The connection-failure classification (SQLState class `08`, `53`, `57P01`, `57P03`) is shared with the existing
  read endpoint - extract it into a common helper instead of copying the private one.

## Files to touch

- `config/MemoryConfig.java` - the two salts.
- `connections/AuthService.java` - resolve a presented token against UUID / RO / RW, expose which role matched;
  the unsalted resolver is replaced by salted ones.
- `auth/TokenAuthFilter.java` - explicit three-way routing: `/mcp` accepts the UUID, `/api/query` accepts RO,
  `/api/data` accepts RW; no fall-through.
- `api/DataWriteController.java` - new, the flow above.
- `config/WebConfig.java` - keep `/api/query` on the open CORS mapping, exclude `/api/data`.
- `tools/MemoryMcpServerTools.java` - `get_connection_info` returns `query_endpoint`, `query_token`,
  `write_endpoint`, `write_token`.
- `src/main/resources/application.yml` - server instructions: the write endpoint, the two tokens, the rule that
  RW never goes into a page.
- `README.md` - the write endpoint, the token derivation, how to compute a token.
- `src/test/java/.../BaseApplicationTest.java` - the planted `config.yml` must carry `saltRo` and `saltRw`,
  otherwise the Spring context fails to boot and every test fails.
- `src/test/java/.../tests/rest/ReadOnlyQueryApiTest.java` - the read token is now salted; the old unsalted
  `sha256(uuid)` no longer works.
- `src/test/java/.../tests/tools/MemoryToolTest.java` - `get_connection_info` now returns the new key names and
  the salted tokens.

Note: `src/main/resources/application.yml` already carries an uncommitted wording change (the block starting
"Schemas: organize the knowledge"). Keep it, add to it.

## Tests (integration, in the existing style: Testcontainers PostgreSQL 18, Spring context, real HTTP)

- CSV load into a pre-created typed table: values land with the table's types.
- CSV load into a non-existent table: the table is created with `text` columns and the rows land.
- JSON array load: columns come from the first object; a nested value survives as JSON text.
- A row that fails conversion is skipped and counted in `rows_ignored`.
- A function call receives the loaded rows and its result comes back in `result`.
- The RO token is rejected on `/api/data` with 401; the RW token is rejected on `/api/query`.
- The RO and RW tokens are rejected on `/mcp`.
- An invalid table or function name is rejected with 400.
- A failed load leaves no rows and answers non-2xx.
- The auto-created table is dropped after a successful function call, and kept when no function was called.
- An unqualified target table name lands in `public`.
- The server refuses to start when a salt is missing, empty, or when `saltRo == saltRw`.
- A ragged CSV row (field count differs from the header) is a `400` and nothing lands.
- Zero columns (empty body, `[]`, `{}`), a CSV with a header but zero data rows, and duplicate column names are
  `400`. An empty column name and a column name longer than 63 bytes are `400`.
- A function name that does not exist, is ambiguous, or takes arguments is `400`.
- `Content-Type: text/csv; charset=utf-8` is accepted (parameters are ignored when matching the format).
- A bare JSON object body (not an array) is `400`.
- A table named with a reserved word (`order`) works - the identifiers are quoted.

## Working rules

- Work only inside this repository. Temporary files only inside a directory that `.gitignore` ignores.
- **Do not commit and do not push.** The orchestrator does that.
- No new dependencies: Jackson is already there, and pgjdbc has `CopyManager` for `COPY ... FROM STDIN`.
- Keep it as small as the contract allows; delete anything that is not needed.
- Do not touch unrelated code. The existing behaviour of `/mcp` and `/api/query` must not change apart from
  which credential they accept.

## Reviewer notes (appended after the review round)

<!-- appended by the orchestrator -->

### Round 1 - DeepSeek (`docs/reviews/2026-10-31-write-endpoint-deepseek.md`), 14 findings, all `fix`

All fourteen were folded into this task:

| # | Finding | Verdict | Where it landed |
|---|---|---|---|
| 1 | Required salts break the test harness: the planted `config.yml` has none, so the context never boots | fix | "Files to touch" -> `BaseApplicationTest.java` |
| 2 | Existing tests pin the unsalted read token and the old `get_connection_info` shape | fix | "Files to touch" -> `ReadOnlyQueryApiTest.java`, `MemoryToolTest.java`; new key names in the credential section |
| 3 | `rows_ignored` only exists in a translatable NOTICE; `rows_received` is derived | fix | "The contract in short" -> counting the rows (`lc_messages = 'C'`, `rows_written` from the `COPY N` tag, verify pgjdbc surfaces the notice) |
| 4 | A ragged CSV row is a hard error, `ON_ERROR ignore` does not skip it | fix | "The contract in short" + a test |
| 5 | A procedure may commit internally and break the transaction | fix | "Server flow" step 6 - inside our explicit transaction a procedure's `COMMIT` fails, so the load rolls back |
| 6 | `saltRo == saltRw` collapses RO and RW | fix | credential section: startup validation, non-empty and different |
| 7 | Three-way auth routing can fall through to the wrong credential | fix | credential section: explicit routing, no fall-through, unsalted resolver replaced |
| 8 | The function call and the emitted SQL must be schema-qualified (leaked `search_path`) | fix | "Server flow" steps 3 and 6; "Safety rules" |
| 9 | Header names are validated but not quoted, so reserved words break | fix | "Safety rules": quote them too |
| 10 | Function resolution: overloads, zero-arg, `prokind` unspecified | fix | "Server flow" step 6 + a test |
| 11 | `get_connection_info` key names unspecified | fix | credential section: `query_endpoint`, `query_token`, `write_endpoint`, `write_token` |
| 12 | The header must be parsed with PostgreSQL's CSV dialect, not split on commas | fix | "Server flow" step 2 |
| 13 | Zero columns and duplicate column names unhandled | fix | "Server flow" step 2 + tests |
| 14 | The error table is incomplete; the 503 classifier is private in the read controller | fix | error list in "The contract in short"; "Safety rules" - share the classifier |

Rejected: none. No finding contradicted the code or the owner's constraints.

