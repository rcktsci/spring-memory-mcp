# spring-memory-mcp

Minimal MCP server exposing a single tool `memory` — full access to a dedicated PostgreSQL 18 database, one database per agent token.

The agent may execute any SQL (DDL, DML, SELECT): create tables, views, functions, procedures, indexes, triggers, enable extensions, and store any data in any form that persists between sessions and conversations. Every created object must get a `COMMENT ON` (mandatory convention published in server instructions). Two REST endpoints sit next to the MCP tool: `/api/query` lets the agent share data with the user (e.g. embedded into HTML pages), and `/api/data` loads a bulk file into the agent's database without the bytes passing through the model context.

## Multi-agent isolation and credentials

Every agent connects with its own UUID token. The token selects the agent's database — an agent can only ever reach its own database.

Each endpoint accepts exactly one credential and rejects the others with `401`:

| Endpoint | Accepts | Meant for |
|----------|---------|-----------|
| `/mcp` | the agent UUID | the MCP client (the harness), never the model |
| `/api/query` | the read token (RO) | the model; published in user pages |
| `/api/data` | the write token (RW) | the model, for shell use only |

The two derived tokens are salted SHA-256 hashes of the agent UUID:

```
query_token = SHA256(lowercase uuid + ":" + saltRo)   # hex
write_token = SHA256(lowercase uuid + ":" + saltRw)   # hex
```

The salts are global (one pair per deployment) and live in the config next to the agent list. Both are required and must differ — with equal salts the published read token would unlock writes, so the server refuses to start.

The agent never sees its own UUID: it is supplied by the MCP client in headers and no tool ever returns it. The only secrets the agent can obtain — and share with the user — are the two derived tokens via the `get_connection_info` tool. The read token is safe to embed into pages; the write token is for the shell only and must never be embedded into a page.

## Tool

### `memory`
Execute arbitrary SQL against the agent's PostgreSQL database. Multiple statements separated by `;` are allowed; all results are returned.

- Query returning a result set: `{ "rows": [ ... ] }`
- DDL/DML: `{ "updateCount": N }`
- Multiple statements: `{ "results": [ ... ] }`
- On error: `{ "error": "message" }`

### `get_connection_info`
Returns the two REST endpoints and their tokens: `query_endpoint`, `query_token`, `write_endpoint`, `write_token`. It never returns the agent UUID.

## REST API

### `POST /api/query`
Strictly read-only. Executed inside a PostgreSQL `READ ONLY` transaction — any write attempt is rejected by the database itself, even if appended to the query text.

```bash
curl -X POST https://<host>/api/query \
  -H "Content-Type: application/json" \
  -H "X-Access-Token: <query_token>" \
  -d '{"query": "SELECT * FROM notes"}'
```

Response: `{ "rows": [...], "rowCount": N }` on success (`200`).

On failure the status code says what happened, and the body carries the message:

| Status | Meaning |
|--------|---------|
| `400`  | The database rejected the query: read-only transaction, broken SQL |
| `401`  | Missing or invalid access token |
| `503`  | The database cannot be reached - the request can be retried |

CORS is open on `/api/query` so that endpoint can be used from browser pages. `/api/data` is excluded from CORS on purpose: a write must not be callable from a page.

### `POST /api/data`

Bulk load into the agent's database. The request body is the file itself, so no data crosses the model context:

```bash
curl -X POST https://<host>/api/data \
  -H "Authorization: Bearer <write_token>" \
  -H "Content-Type: text/csv" \
  -H "X-Target-Table: sales.orders" \
  -H "X-Function-Name: sales.ingest" \
  --data-binary @orders.csv
```

The token may also be sent as `X-Access-Token: <write_token>` instead of the `Authorization` header.

| Header | Meaning |
|--------|---------|
| `Content-Type` | `text/csv` (header row names the columns) or `application/json` (array of objects; columns are the keys of the first object). Parameters such as `charset=utf-8` are ignored. |
| `X-Target-Table` | Required `[schema.]table`; an unqualified name lands in `public`. The session `search_path` is never used. |
| `X-Function-Name` | Optional `[schema.]function`: a no-argument function or procedure called right after the load, in the same transaction. An unqualified name resolves to `public` in its own schema, independently of the table's schema. |

Behaviour:

- The table is created when it does not exist, with every input column typed `text`; an existing table keeps its own types. An input column the table does not have is a `400`; columns missing from the input keep their column defaults.
- An empty column name or a column name longer than 63 bytes is a `400` (PostgreSQL would reject or silently truncate it).
- The whole load is one transaction: a failure anywhere rolls everything back — nothing lands.
- Rows whose value cannot be converted to the target column type are skipped and counted (`ON_ERROR ignore`); a row with a different field count than the header aborts the load.
- A CSV with a header but no data rows is a `400`. The first line of a CSV body is always the header, so a "missing CSV header" cannot happen — "missing header" means `X-Target-Table`.
- Excel-style CSV is accepted: a leading UTF-8 BOM is ignored and CRLF line endings are handled (and counted) correctly.
- An auto-created table is dropped after a successful function call (`DROP TABLE IF EXISTS`, so a function that already removed it is fine), so the table is a pure staging area; without a function it is kept.
- No size, row or rate limits; no idempotency key — a retried script may duplicate rows.

Success (`200`):

```json
{"rows_received": 12000, "rows_ignored": 3, "rows_written": 11997, "result": null}
```

`rows_written` is PostgreSQL's `COPY` count. `rows_received` is counted from the input while it streams (CSV records by PostgreSQL's CSV rule, JSON objects by the server), so `rows_ignored = rows_received - rows_written` never depends on log messages. `result` carries the rows returned by the called function (`null` when no function was called or the routine is a procedure); a `RETURNS void` function yields one row with a null value keyed by the function name.

| Status | Meaning |
|--------|---------|
| `400`  | Invalid or missing table/function name, unsupported `Content-Type`, empty body or zero columns, a header with no data rows, empty/too-long/duplicate column names, unknown column, ragged CSV row, function not callable (aggregate/window) or missing/ambiguous/taking arguments, the database rejected a statement, the function raised |
| `401`  | Missing or invalid write token |
| `503`  | The database cannot be reached - the request can be retried |
| `500`  | Anything unexpected |

The error body is always `{"error": "..."}` and carries the PostgreSQL message.

Paths under `/api/` other than exactly `/api/query` and `/api/data` (for example a trailing slash) answer `401`: the API has exactly two paths and no token is accepted on anything else.


## Deployment

`docker-compose.yml`:

```yaml
services:
  memory-db:
    image: postgres:18-alpine
    environment:
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: db-admin-password
    volumes:
      - memory-db-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 5s
      timeout: 3s
      retries: 10

  memory-mcp:
    image: ghcr.io/rcktsci/spring-memory-mcp:2026-10-31
    depends_on:
      memory-db:
        condition: service_healthy
    environment:
      PUBLIC_URL: https://memory.example.com
    ports:
      - "8080:8080"
    volumes:
      - ./config.yml:/application/config.yml:ro

volumes:
  memory-db-data:
```

`config.yml` next to the compose file:

```yaml
memory:
  salt-ro: "a-long-random-string-for-read-tokens"
  salt-rw: "another-long-random-string-for-write-tokens"
  agents:
    - token: "3f2a9c8e-6c1d-4a2b-9e7f-1b2c3d4e5f60"
      host: memory-db
      port: 5432
      database: agent_alpha
      username: agent_alpha
      password: agent-alpha-password
    - token: "7c1e4b9a-0d3f-4c8e-a5b6-9f0e1d2c3b4a"
      host: memory-db
      port: 5432
      database: agent_beta
      username: agent_beta
      password: agent-beta-password
```

Prepare the databases once:

```bash
docker compose exec memory-db psql -U postgres -c "CREATE USER agent_alpha PASSWORD 'agent-alpha-password'"
docker compose exec memory-db psql -U postgres -c "CREATE DATABASE agent_alpha OWNER agent_alpha"
docker compose exec memory-db psql -U postgres -c "CREATE USER agent_beta PASSWORD 'agent-beta-password'"
docker compose exec memory-db psql -U postgres -c "CREATE DATABASE agent_beta OWNER agent_beta"
```

The public URL (where the server is reachable) is taken from the `PUBLIC_URL` environment variable or `memory.public-url`; it is published to agents via server instructions so they can build user-facing links and shell commands.

### Upgrading a deployed instance

The build refuses to start without both salts, so upgrade in this order:

1. stop the running container;
2. add `salt-ro` and `salt-rw` (different, non-empty, long random values) to the `memory:` block of `config.yml`;
3. start the new image.

Once tokens have been published the salts are effectively permanent: changing `saltRo` invalidates every
page token (embedded pages stop working), changing `saltRw` breaks every shell script holding a write token,
and a lost salt cannot be re-derived — only re-issued by publishing the new token.

Compute a token for an agent (or ask the agent to call `get_connection_info` — it returns the ready-to-use credentials). The derivation is exactly `SHA256(lowercase uuid + ":" + salt)`, UTF-8 bytes, lowercase hex:

```bash
# read token (RO) - the one for pages
printf '%s' "3f2a9c8e-6c1d-4a2b-9e7f-1b2c3d4e5f60:a-long-random-string-for-read-tokens" | sha256sum

# write token (RW) - shell only, never in a page
printf '%s' "3f2a9c8e-6c1d-4a2b-9e7f-1b2c3d4e5f60:another-long-random-string-for-write-tokens" | sha256sum
```

## Build and run

```bash
./mvnw clean package
java -jar target/spring-memory-mcp-1.0.0.jar
```

MCP endpoint (streamable HTTP): `http://localhost:8080/mcp`
