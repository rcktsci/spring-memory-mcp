# spring-memory-mcp

Minimal MCP server exposing a single tool `memory` — full access to a dedicated PostgreSQL 18 database, one database per agent token.

The agent may execute any SQL (DDL, DML, SELECT): create tables, views, functions, procedures, indexes, triggers, enable extensions, and store any data in any form that persists between sessions and conversations. Every created object must get a `COMMENT ON` (mandatory convention published in server instructions). Additionally, a public read-only REST endpoint lets the agent share its data with the user (e.g. embed into HTML pages).

## Multi-agent isolation

Every agent connects with its own UUID token. The token selects the agent's database — an agent can only ever reach its own database.

- MCP endpoint (`/mcp`): token is taken from the `Authorization: Bearer <token>` header or the `X-Access-Token` header.
- REST endpoint (`/api/query`): the token is the **SHA-256 hex hash** of the agent's UUID token (so the agent never exposes its real token in shared links).

## Tool

### `memory`
Execute arbitrary SQL against the agent's PostgreSQL database. Multiple statements separated by `;` are allowed; all results are returned.

- Query returning a result set: `{ "rows": [ ... ] }`
- DDL/DML: `{ "updateCount": N }`
- Multiple statements: `{ "results": [ ... ] }`
- On error: `{ "error": "message" }`

## REST API

### `POST /api/query`
Strictly read-only. Executed inside a PostgreSQL `READ ONLY` transaction — any write attempt is rejected by the database itself, even if appended to the query text.

```bash
curl -X POST https://<host>/api/query \
  -H "Content-Type: application/json" \
  -H "X-Access-Token: <sha256-hex-of-uuid-token>" \
  -d '{"query": "SELECT * FROM notes"}'
```

Response: `{ "rows": [...], "rowCount": N }` or `{ "error": "message" }`.

CORS is open on `/api/**` so the endpoint can be used from browser pages.

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
    image: ghcr.io/rcktsci/spring-memory-mcp:latest
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

The public URL (where the server is reachable) is taken from the `PUBLIC_URL` environment variable or `memory.public-url`; it is published to agents via server instructions so they can build user-facing links: `POST {PUBLIC_URL}/api/query` + `X-Access-Token: sha256(agent token)`.

Compute the public read-only token for an agent:

```bash
echo -n "3f2a9c8e-6c1d-4a2b-9e7f-1b2c3d4e5f60" | sha256sum
```

## Build and run

```bash
./mvnw clean package
java -jar target/spring-memory-mcp-1.0.0.jar
```

MCP endpoint (streamable HTTP): `http://localhost:8080/mcp`
