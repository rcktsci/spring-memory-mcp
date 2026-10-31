# Fix round 1 - write endpoint

Sources: `docs/reviews/2026-10-31-write-endpoint-glm-free.md` (review of the task, produced after the
implementation), `docs/reviews/2026-10-31-write-endpoint-deepseek-code.md` (review of the diff), plus the
orchestrator's own checks. Both reviewers agree on the two main issues.

Every item below carries a verdict. Only `fix` items are to be implemented. `rejected` and `not worth fixing`
are recorded so nobody re-opens them.

## fix

1. **`rows_ignored` must not be parsed out of a NOTICE.** The notice text is translatable
   (`errmsg_plural` in `copyfrom.c`) and `lc_messages` can only be set by superusers or roles with an
   explicit `SET` privilege, so on a real agent role the pin is skipped and the leading-integer parse
   silently yields `0` -> `rows_received == rows_written` while rows were dropped.
   Replace the NOTICE path entirely:
   - count the input records yourself as the bytes stream to the COPY. For CSV that is a record counter
     that follows the same dialect rule as item 2 (a record ends at an unquoted `\n`/`\r\n`; quoted
     commas, quotes and newlines do not end it). For JSON the count already exists (the emitted rows);
   - `rows_written` stays the `COPY N` command tag;
   - `rows_ignored = rows_received - rows_written`;
   - delete the `lc_messages` / `is_superuser` code and the warning-chain reading.
   Add a test with **two or more** skipped rows (the current test only skips one).

2. ~~**The CSV header parser must follow PostgreSQL's CSV input rule**: a quote is special **only at the
   start of a field**...~~ **REJECTED after the fact - see the rejected list at the bottom.** The
   implementer measured PostgreSQL 18.6 and the orchestrator reproduced it (below). The parser was still
   rewritten to match the measured behaviour and now has tests for a mid-field quote, a doubled quote and
   a quoted newline in the header.

3. **Function resolution must exclude aggregates and window functions.** Add `AND p.prokind IN ('f','p')`
   to the `pg_proc` lookup; a unique zero-argument match of another kind (`count`, or an agent-created
   aggregate) answers `400` "not a callable function". Add a test with `count`.

4. **Pin the session GUCs that change how the COPY stream is interpreted**, the same reasoning the task
   already applies to `search_path`: `memory` can leave `SET client_encoding`, `SET datestyle` or
   `SET intervalstyle` on a pooled connection, and the next bulk load then decodes or parses the same
   bytes differently (silent corruption, or every row discarded). Add `SET LOCAL client_encoding = 'UTF8'`,
   `SET LOCAL datestyle = 'ISO, YMD'`, `SET LOCAL intervalstyle = 'postgres'` inside the write transaction.
   Add one test that poisons a pooled session through `memory` (`SET datestyle = 'german'`) and then
   loads a CSV with a date column.

5. **Auto-created table drop must tolerate the function having dropped it.** Use `DROP TABLE IF EXISTS`;
   today an agent function that removes the table makes the whole load fail after the work is done.

6. **Input column name guards**: an empty column name -> `400` (PostgreSQL rejects
   `zero-length delimited identifier`); a column name longer than 63 bytes -> `400` (PostgreSQL silently
   truncates identifiers, so two long JSON keys that differ only after byte 63 collide in `CREATE TABLE`).
   Add both to the tests.

7. **CORS tests.** Nothing asserts that `/api/data` is excluded from the open mapping, and the existing
   `/api/query` preflight test only asserts `!= 401`, so a regression back to `/api/**` would pass.
   Assert explicitly: a preflight to `/api/query` returns `Access-Control-Allow-Origin`, and a preflight
   to `/api/data` does not.

8. **Two untested behaviours**: a table column omitted from the input keeps its `DEFAULT`, and a
   function that raises leaves no rows behind (the auto-created table is gone too). Add both.

9. **Documentation, task and README** (no behaviour change):
   - "header-only CSV" is **zero data rows**, not zero columns - it stays `400`; say it that way in the
     contract and add it to the test list;
   - "missing header" in the error table means the `X-Target-Table` header only: a CSV body always has its
     first line taken as the header, so a "missing CSV header" is not detectable;
   - the function name is resolved in **its own** schema (unqualified -> `public`), independently of the
     target table's schema;
   - `RETURNS void` function: `result` is one row with a null value keyed by the function name, not `null`;
   - `/api/data` accepts `X-Access-Token` as well as `Authorization: Bearer` (the filter already does) - say
     so;
   - paths under `/api/` that are not exactly `/api/query` or `/api/data` answer `401` (documented, see the
     rejected list);
   - **README upgrade note for deployed instances**: the build now refuses to start without both salts, so
     the order is stop -> add `salt-ro` and `salt-rw` to `config.yml` -> start the new image. State that
     once tokens are published the salts are effectively permanent: changing `saltRo` invalidates every
     page token, changing `saltRw` breaks every shell script, and a lost salt cannot be re-derived.

## rejected (do not change)

- **"A quote is special only at the start of a field"** (fix item 2, GLM finding 2): that is the RFC 4180
  rule, not PostgreSQL's. Measured on a real PostgreSQL 18 container:

  ```
  CREATE TABLE q (a text, b text, c text);
  COPY q FROM STDIN WITH (FORMAT csv, HEADER true);
  a,b"c",d
  1,2,3
  \.
  -- COPY 1; SELECT * shows a=1 b=2 c=3
  ```

  PostgreSQL accepts the header as **three** fields (`a`, `bc`, `d`): a mid-field quote opens quoting and
  the content around it is joined. The implementer reported the same from PostgreSQL 18.6. The parser was
  nevertheless rewritten into an explicit state machine that matches the measured behaviour, and the new
  tests pin a mid-field quote, a doubled quote and a quoted newline in the header.
- **"Pin `lc_messages` unconditionally"** (GLM finding 4): PostgreSQL 18 documents that only superusers and
  roles with an explicit `SET` privilege may change it, and the implementation measured
  `permission denied to set parameter "lc_messages"` on a plain role. An unconditional pin would fail the
  transaction for every real agent. The hazard was removed by counting rows instead (fix item 1).
- **"A procedure that COMMITs breaks atomicity"** (DeepSeek code review): PostgreSQL 18, `CALL`:
  "If `CALL` is executed in a transaction block, then the called procedure cannot execute transaction
  control statements." The write path always runs inside an explicit transaction, so such a `COMMIT` fails,
  the load rolls back, and nothing is left behind.

## not worth fixing (recorded)

- `/api/query/` (trailing slash) now answers `401` instead of `404` - documented, the API has exactly two
  paths.
- `RETURNS void` shape, a routine with default arguments reported as "not found", deep JSON nesting
  answering `400`, and a harmless post-commit `rollback()` warning.

## Rules for this round

- Same constraints as the first round: no commit, no push, no new dependencies, work only inside this
  repository, keep the existing uncommitted wording in `application.yml`.
- Update `docs/tasks/2026-10-31-write-endpoint-implementation.md` with what changed in this round and the
  final test counts.
- Report the final test counts and anything still uncertain.
