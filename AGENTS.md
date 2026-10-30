# AGENTS.md

## What this repository is

`spring-memory-mcp` is an MCP server that gives an agent persistent memory: one tool, `memory`, that runs
any SQL against the agent's own dedicated PostgreSQL database. This file is the reference for **how to lay
out that knowledge** - the server itself stores whatever an agent creates, the convention below is what
keeps it readable for the next session.

## Knowledge schema

Everything an agent learns lives in the `knowledge` schema of its own database. The database is long-lived:
it survives sessions, conversations, restarts and container rebuilds. Nothing is reset unless an agent
deliberately drops it.

```sql
CREATE SCHEMA IF NOT EXISTS knowledge;
```

### Layout

| Object                | Purpose                                                            |
|-----------------------|--------------------------------------------------------------------|
| `knowledge.<topic>`   | One table per topic - notes, decisions, people, tasks, whatever fits |
| `knowledge.meta`      | Metadata: what every table in the schema is for                     |

`knowledge.meta`:

| Column       | Meaning                                                     |
|--------------|-------------------------------------------------------------|
| `table_name` | Table in the `knowledge` schema                            |
| `purpose`    | One sentence: what is stored there and when it is used     |
| `created_at` | When the table appeared                                     |
| `updated_at` | When the table was last changed                             |

```sql
CREATE TABLE IF NOT EXISTS knowledge.meta (
    table_name text PRIMARY KEY,
    purpose    text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
```

### Rules

1. One table per topic, named after the topic: `knowledge.decisions`, `knowledge.people`, `knowledge.notes`.
2. Every table gets a row in `knowledge.meta`, written in the same transaction that creates the table.
3. Every object gets a comment - `COMMENT ON TABLE knowledge.decisions IS '...'` and
   `COMMENT ON COLUMN knowledge.decisions.summary IS '...'`. A future session must be able to read the
   schema without guessing what a column means.
4. Loosely structured notes go into JSONB columns; facts with a fixed shape go into normal columns.
5. Update knowledge in place with UPSERT (`INSERT ... ON CONFLICT ... DO UPDATE`), do not append
   duplicates of the same fact.
6. Delete or archive knowledge that turned out to be wrong. The database is long-lived state, not a log.
7. Never drop the `knowledge` schema or `knowledge.meta` - they are the index of everything else.

### Before writing anything

```sql
SELECT table_name, table_comment FROM information_schema.tables WHERE table_schema = 'knowledge';
SELECT * FROM knowledge.meta ORDER BY updated_at DESC;
```

Read what is already there first, then decide whether the new fact is a new table, a new row, or an
update of an existing one.
