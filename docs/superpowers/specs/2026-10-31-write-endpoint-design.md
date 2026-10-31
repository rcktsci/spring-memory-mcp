# Design: bulk write endpoint for the memory MCP

Date: 2026-10-31
Status: draft, for approval

## Intent

An agent must be able to load bulk data into its own PostgreSQL database **without the data passing
through the model context window**. Today the only write path is the `memory` tool (arbitrary SQL), so
every byte of a bulk load crosses the LLM context. This adds a second write path: the agent (or a script it
runs) streams a file into a dedicated endpoint, and the server puts it into the database.

Non-goals: replacing `memory`, changing the read endpoint, any schema semantics (see below).

## Decisions

| # | Decision | Why |
|---|----------|-----|
| 1 | The stream arrives as the request body: raw bytes, `curl --data-binary @file.csv` | The agent already holds the file; the server never walks a file system. |
| 2 | No size, row or rate limits | Owner's decision. The only backstop is the agent's own database (disk/quota). A runaway upload can fill the PostgreSQL instance. |
| 3 | Three credentials, each working in exactly one place (matrix below) | A published read token must never open the MCP endpoint or a write. |
| 4 | Operation kinds: load into a table, optionally call a function afterwards | A function the agent itself defined covers everything else (MERGE, fan-out, casting). No separate upsert operation. |
| 5 | Column names come from the input | The agent does not repeat them in headers. |
| 6 | Types: an existing table keeps its own types; an auto-created table gets `text` for every column | `text` accepts anything and has no length limit, so nothing is ever rejected for a type mismatch. The agent creates a typed table itself when it wants types. |
| 7 | A schema is just part of the table name. No schema semantics anywhere | The schema is the agent's project; the server does not interpret it. |
| 8 | Regular tables only, never `pg_temp` | A session-scoped table is invisible to other pooled connections (Hikari hands out different sessions); pinning the whole request to one connection would make the data unreachable from `memory` afterwards. |
| 9 | No idempotency key | Owner's decision. A retried failed script can duplicate rows. |

## Credential model

| Credential | Derivation | Held by | `/mcp` | `/api/query` | `/api/data` |
|---|---|---|---|---|---|
| agent UUID | - | the MCP client (the harness), never the model | yes | no | no |
| RO | `SHA256(uuid + ":" + salt_ro)` | the model; published in user pages | no | yes | no |
| RW | `SHA256(uuid + ":" + salt_rw)` | the model, for shell use only | no | no | yes |

- The root UUID stays in the server config and never leaves it.
- `get_connection_info` returns both derived tokens (and the endpoints). It never returns the UUID.
- The two salts are **global**, one pair per deployment, in the config next to the agent list. They are
  required: the server refuses to start without them. There is no unsalted fallback - no read token has
  been issued yet, so nothing to keep working.
- The server instructions must say: RW is for loading data from the shell and must never be embedded in a
  page; RO is for pages.
- Existing MCP clients keep using the UUID - no change for them.
- Exact derivation, so operator-side tooling can compute a token: lowercase UUID, a colon, then the salt
  string as written in the config; UTF-8 bytes; hex digest.

## Protocol

```
POST /api/data
Authorization: Bearer <RW token>
Content-Type: text/csv | application/json
X-Target-Table: [schema.]table        # where the data goes
X-Function-Name: [schema.]function     # optional: called right after the load

<body> = CSV with a header row, or a JSON array of objects
```

- The endpoint URL is `memory.public-url` + `/api/data` - the same base the agent already uses for the
  read endpoint. No new configuration property. If an agent's environment cannot reach the public
  address, a dedicated write base URL can be added later.
- `/api/data` is **excluded** from the open CORS mapping (only `/api/query` keeps `allowedOrigins: *`):
  a write endpoint must not be callable from a browser page.

Response on success:

```json
{"rows_received": 12000, "rows_ignored": 3, "rows_written": 11997, "result": null}
```

`result` carries whatever the called function returned (rows as JSON), when a function was called.

Identifier validation for the header values (anything else is `400`, so a name can never carry SQL):

```
^[a-z_][a-z0-9_]*(\.[a-z_][a-z0-9_]*)?$
```

## Server flow

1. Authenticate: the presented token must match `SHA256(uuid + ":" + salt_rw)` for exactly one agent.
   Otherwise `401`.
2. Derive the input format from `Content-Type` and the column set from the input:
   - CSV: the header row;
   - JSON: the keys of the first object. A later object with an unknown key is an error; a missing key is
     `NULL`. The body is parsed as a stream, so the whole array is never held in memory.
3. Resolve the target table name: an unqualified name resolves to `public`. The schema is never taken from
   the session `search_path` - that is connection state the `memory` tool can change.
4. Ensure the target table exists. If it does not: create the schema if needed, then the table with the
   input columns, all `text`. If it does: use it as is.
   - The input may contain a column the table does not have: that is a `400` (the agent creates the table
     it wants, or fixes the file). The table may contain columns the input does not have: they keep their
     column default.
5. One transaction:
   - `COPY <target> (<columns present in the input>) FROM STDIN WITH (FORMAT csv, HEADER true, ON_ERROR ignore)`;
     rows that fail conversion are discarded and counted (PostgreSQL reports the count in a NOTICE).
   - if `X-Function-Name` is set: call it in the same transaction, in the same session. It reads the data
     from the target table (it knows the name - the agent wrote it) and does whatever it wants with it.
   - if the table was auto-created and the function ran successfully: drop the table.
6. Commit.

Failure anywhere in the transaction rolls it back: nothing lands in the database.

## Formats and column derivation

| Input | Columns | Notes |
|---|---|---|
| `text/csv` | the header row | the CSV body is passed to `COPY` as bytes; PostgreSQL owns the CSV dialect |
| `application/json` | the keys of the first object | array of objects; parsed as a stream |

- CSV bytes go to `COPY` untouched - the server does not re-implement RFC 4180 quoting. PostgreSQL's rules
  apply: an unquoted empty field is `NULL`, a quoted empty field is an empty string.
- A JSON array is streamed by the server and re-emitted as CSV rows (RFC 4180 quoting, so commas, quotes
  and newlines inside values survive). JSON `null` becomes an unquoted empty field (SQL `NULL`); a JSON
  string that is empty becomes a quoted empty field (empty string). A nested object or array becomes its
  JSON text.
- Input column names are **quoted** as identifiers (`"` with inner `"` doubled), so any name is accepted
  safely, including non-ASCII ones.
- `ON_ERROR ignore` applies to the text and csv formats, which is what both input kinds are converted to.

## Security

- The write endpoint accepts the RW token only; the RO token (published in pages) is rejected with `401`.
- The MCP endpoint accepts the UUID only - RO and RW are rejected there, so a published token cannot reach
  the `memory` tool.
- The read endpoint accepts the RO token only.
- Table and function names from the headers are validated against the regex above; column names from the
  input are quoted. The server never concatenates an unvalidated name into SQL.
- One database per agent: a write can only ever touch the writing agent's own database.
- A leaked RW token lets the holder load data and call functions of that agent. It does not give arbitrary
  SQL - that stays behind the UUID on `/mcp`.
- The endpoint stays on the internal network only, like the rest of the MCP surface; no external route.

## HTTP error contract

| Situation | Status | Body |
|---|---|---|
| bad or missing token | 401 | `{"error": "..."}` |
| invalid table/function name, missing header, unsupported `Content-Type`, empty body, unknown column in the input, function raised | 400 | `{"error": "..."}` |
| the database cannot be reached | 503 | `{"error": "..."}` |
| anything unexpected | 500 | `{"error": "..."}` |

A failed load is always a non-2xx answer - never `200` with a failure marker inside, so a script can
branch on the status code. The error body carries the PostgreSQL message.

## Explicitly not doing

- no upsert operation (express it as a `MERGE` inside a function);
- no idempotency key;
- no size, row or rate limits;
- no NDJSON input;
- no `X-Data-Columns` / `X-Data-Format` headers;
- no temporary tables and no staging schema;
- no server-side file fetch by path or URL;
- no schema semantics.

## Changes to the existing server

- `MemoryConfig`: add the two global salts `saltRo` and `saltRw`; both required.
- `AuthService`: resolve a presented token against the UUID, the RO hash and the RW hash; expose which role
  matched.
- `TokenAuthFilter`: `/mcp` accepts the UUID; `/api/query` accepts RO; `/api/data` accepts RW.
- New `DataWriteController` implementing the flow above.
- `WebConfig`: keep `/api/query` on the open CORS mapping, exclude `/api/data`.
- `get_connection_info`: return the RO token, the RW token and both endpoints.
- Server instructions: mention the write endpoint, the two tokens and the rule that RW never goes into a page.

## Tests

Integration tests in the existing style (Testcontainers PostgreSQL 18, Spring context, real HTTP):

- CSV load into a pre-created typed table: values land with the table's types;
- CSV load into a non-existent table: the table is created with `text` columns and the rows land;
- JSON array load: columns come from the first object, a nested value survives as JSON text;
- a row that fails conversion is skipped and counted in `rows_ignored`;
- a function call receives the loaded rows and its result comes back in `result`;
- the RO token is rejected on `/api/data` with `401`; the RW token is rejected on `/api/query`;
- the RO and RW tokens are rejected on `/mcp`;
- an invalid table or function name is rejected with `400`;
- a failed load leaves no rows and answers non-2xx;
- the auto-created table is dropped after a successful function call, and kept when no function was called;
- an unqualified target table name lands in `public`.

## Left to the builder

- Which JSON library and streaming reader for the array form (Jackson is already on the classpath through
  Spring Boot's JSON support).
- How to run `COPY ... FROM STDIN` from Java (pgjdbc `CopyManager`).
- How the NOTICE with the ignored-row count is captured and turned into `rows_ignored`.
- Exact wording of the instruction and tool-description changes.
