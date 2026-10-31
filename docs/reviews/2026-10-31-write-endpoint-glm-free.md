# Review of the task `docs/tasks/2026-10-31-write-endpoint.md` (second round, GLM)

Reviewed: the task, the design (`docs/superpowers/specs/2026-10-31-write-endpoint-design.md`), the committed code, and — important — **the working tree already contains an almost-complete implementation** (`DataWriteController.java`, `SqlErrors.java`, `DataWriteApiTest.java`, `MemoryConfigTest.java` untracked; every file in "Files to touch" already modified, uncommitted). Where the task and that WIP code already disagree, the finding says so; the implementer must reconcile both ways. Nothing was changed outside this report; nothing committed or pushed.

PostgreSQL facts verified against the PG 18 docs (`sql-copy.html`, `config-setting.html`) and the PG source via the `zread` tool (`copyfrom.c`, `copyfromparse.c`).

Tool problems encountered: `websearch` returned no results for every query (three attempts); fetching `guc_tables.c` timed out both via `webfetch` and `zread`, so the `lc_messages` context claim below carries a one-line verification command instead of a source citation.

## Findings

### 1. Session GUC leftovers change the bulk load's *data semantics* — the task fixes only `search_path`
- Task:61-63 (search_path), 52-79 (flow), `AgentConnectionsManager.java:28-41` (pooled sessions reused across MCP `/mcp` requests).
- The `memory` tool can run `SET client_encoding = 'LATIN1'` or `SET datestyle = 'german'` on a pooled connection; Hikari hands that session to a later bulk load. PostgreSQL decodes the raw COPY stream with the session `client_encoding` and feeds the CSV text through the session `DateStyle`/`IntervalStyle` before the type input functions (COPY docs: "Input data is interpreted according to ENCODING option or the current client encoding", "COPY input ... is affected by DateStyle"). Failure: the next bulk load garbles every text field (a `200` with silently corrupted data) or produces conversion errors that `ON_ERROR ignore` silently discards (`rows_written = 0, rows_ignored = N`, a `200` with nothing landed). The task already acknowledges this class of bug for identifiers and fixed it by schema-qualification; the data path has the same hole and the task, its counting rules (task:44 "pin ... `lc_messages`"), and all listed tests miss it.
- Smallest fix: pin the same transaction with `SET LOCAL client_encoding = 'UTF8'`, `SET LOCAL datestyle = 'ISO, YMD'` (and `intervalstyle = 'postgres'`) next to the `lc_messages` pin, plus one test that poisons the pool via `memory` SQL and then loads.

### 2. The CSV header dialect rule is incomplete, and the in-tree implementation already diverges from what the task requires
- Task:59-60 ("parsed with PostgreSQL's default CSV dialect ... a header cell may itself contain a comma or a newline, so do not split on commas"); WIP `DataWriteController.java:289-342` (`readCsvHeader`/`parseCsvHeader`).
- The task names the delimiter/quote/escape but not the decisive rule: in PostgreSQL's CSV **input**, a quote character is special only at the start of a field (immediately after a delimiter); a quote appearing after other data in the same field is literal data. A toggle-a-boolean parser (exactly what the WIP wrote) therefore disagrees with PG on ordinary files. Concrete failure with the WIP code: header `a,b"c,d\n1,2,3\n` — at `b"c` the parser enters "quoted" mode, never finds a closing quote, swallows the rest of the file as one header cell, and answers `400 "The CSV body has no data rows"` for a file PG would happily load; other collisions miscount columns (server says 2, PG parses 3 → `extra data after last expected column` → withdrawn 400). The test list has no vector for any of this (`quotedCsvHeaderCellWithComma` covers only a well-formed quoted comma).
- Smallest fix: in the task, spell the rule out — quote starts quoting only at field start, `""` inside quoting is a literal quote, quotes elsewhere are literal (that is all it takes to match PG) — and add two test vectors: a mid-field quote in a header cell, and a header cell containing a quoted newline (also validates that PG's `HEADER true` skip drops the whole logical record, which the task asserts in passing at task:68).

### 3. Function resolution does not exclude aggregates/window functions; a zero-arg aggregate falls through the two-way routing
- Task:70-77 (step 6: `pronargs = 0`, then *prokind* decides `SELECT * FROM fn()` vs `CALL proc()`); `pg_proc.prokind` is `f|p|a|w`.
- Zero-argument aggregates are legal (`count()` has `pronargs = 0, prokind = 'a'`, and an agent can create its own with `CREATE AGGREGATE q(*)`). Such a name passes "exactly one row with `pronargs = 0`" and is then routed to `SELECT * FROM q()` (not a procedure), failing inside SQL with an opaque error instead of the clean `400` the error table promises. The test list claims exhaustive coverage ("does not exist, is ambiguous, or takes arguments") but omits this. The WIP (`functionKind`) has the same gap in code.
- Smallest fix: resolution query becomes `pronargs = 0 AND prokind IN ('f','p')`; a unique zero-arg match of another kind answers `400` "not a callable function"; add the aggregate to the test list line.

### 4. The WIP gates the `lc_messages` pin on `is_superuser`, deviating from the task's unconditional pin
- Task:43-45 ("pin the session with `SET LOCAL lc_messages = 'C'` before the COPY"); WIP `DataWriteController.java:156-163` ("lc_messages can only be changed by a superuser" — pin applied only when the role is superuser).
- `lc_messages` is a user-context GUC (in `pg_settings` its context is `user`; only a handful of GUCs are superuser-only — verify at implementation with `SELECT context FROM pg_settings WHERE name = 'lc_messages'`). For the normal agent role (database owner, not superuser) the WIP therefore never pins, and the whole translatable-notice hazard the task closed (task:44-47) stays open on a deployment whose cluster locale is not `'C'`: the parse of the "leading integer" can break a translated sentence whose placeholders were reordered.
- Smallest fix: remove the `is_superuser` gate; pin unconditionally. (If a test really shows the SET fails for the agent role, take that back to the task — but then the fallback rules must change, not survive as a silent no-pin.)

### 5. Contradiction in the task itself: "header-only CSV" is called zero columns
- Task:57 ("Zero columns (empty body, `[]`, `{}`, header-only CSV) -> 400") vs. task:175 (the corresponding test list: "empty body, `[]`, `{}`" — no header-only CSV).
- A header-only CSV *has* columns; it has zero data rows. Labeling it "zero columns" contradicts the task's own column model (the column list exists before any row is read) and the test list omits the case. The WIP implemented it as a 400 ("The CSV body has no data rows") — so the decision apparently exists but only in code. An implementer who instead believes "zero columns" might cause a `CREATE TABLE`/`COPY ()` failure path there.
- Smallest fix: make task:57 say "empty body, `[]`, `{}`, `[{}]`, header-only CSV — all answered `400` as an empty body" (or, if the owner prefers, a `200` with `rows_written: 0`; either way both places must agree and the test list must carry it).

### 6. "The routine is resolved in the resolved schema" is ambiguous
- Task:70-71, design:98-99. Two readings: (a) the schema of the function name itself (unqualified → `public`), (b) the resolved schema of the *target table* (`X-Target-Table: data.raw` + `X-Function-Name: ingest` would look up `data.ingest`). The WIP implements (a). With reading (b) a load with a correctly-named public function fails `400 "Function not found"` — the failure would be diagnosed from the server, not the spec.
- Smallest fix: one sentence — "the function name is resolved in its own name's schema, an unqualified function name resolves to `public`, independently of the target table's schema."

### 7. Unmapped edge cases that force guessing, all cheap to pin
- **Function drops the staging table.** Task:78-79 ("If the table was auto-created and the function ran successfully: drop the table"). If the agent's function itself dropped the auto-created table, the server's unconditional `DROP` aborts an otherwise-successful load with a non-2xx (transaction rolls back; the function's work is lost). Fix: re-check existence or use `DROP TABLE IF EXISTS`, and state that the drop is skipped when the table is gone.
- **Zero-length input column name.** A JSON object `{"": 1}` produces `"..." ("")` — PostgreSQL rejects with `zero-length delimited identifier`. Unmapped status/message. Fix: `400` "empty column name" before any SQL is sent.
- **63-byte identifier truncation.** Input column names are unrestricted (task:127), PostgreSQL silently truncates quoted identifiers to 63 bytes; two JSON keys differing only after byte 63 both pass the duplicate-name check (task:58) and then fail `CREATE TABLE` with `column "..." specified more than once`. Fix: either reject input column names longer than 61 bytes with a clear `400`, or run the duplicate check on the truncated names; state which.
- **Void-returning function.** `SELECT * FROM audit()` for `RETURNS void` returns one row over one column named by the function; what lands in `result` (`[{"audit": null}]`?) is undefined in both spec files. Fix: one sentence in the task.

### 8. Operational hole: already-deployed instances have no salts and the new build refuses to start
- Task:90-97 (salts required at startup), `README.md:112-166` (deployment section shows only the *new* config template), `Dockerfile:5` (config comes from a mounted volume).
- Any deployed instance updated in place will crash-loop on restart until its operator adds `salt-ro`/`salt-rw` to `config.yml` — nothing in the task or the README documents the upgrade order or the consequence. Two more things must be said in the same place: (a) once tokens are published, the salts are effectively **permanent** — changing `saltRo` invalidates every page token ever published, changing `saltRw` breaks every shell script holding an RW token; (b) a lost salt cannot be recovered or re-derived.
- Smallest fix: a numbered upgrade note in README "Deployment" ("stop → add both salts to config.yml → deploy the new image; treat the salts as permanent from then on") and the same instruction in the task's "Files to touch" → README bullet.

### 9. "missing header" means two different things
- Task:36-37 ("Errors: 400 (invalid name, missing header, ...)"), design:140, README:104.
- A CSV body *without* a header row is undetectable in principle (the server takes the first line as the header by construction — same as PG's `HEADER true` skip); only the absent `X-Target-Table` HTTP header is detectable. The WIP has both behaviors (400 "X-Target-Table must match ...", 400 "The CSV header row is missing"), so an implementer can satisfy the text at random. Fix: split the term — "missing `X-Target-Table`" and, if this case is really wanted, "CSV body empty after the header"— and drop the second if the owner never meant it.

### 10. Filter routing: the "default" bucket is only defined for the endpoints the servlet pattern registers
- Task:94-97 ("The filter routes explicitly, with no fall-through: ... everything else (the MCP endpoint) accepts only the UUID"); WIP `TokenAuthFilter.java:52-58` (`switch (routedPath) { case "/api/query" -> READ; case "/api/data" -> WRITE; default -> AGENT; }`).
- With exact matching, `/api/query/` (trailing slash), `/api/query/foo` etc. land in the default bucket: those get the UUID credential check and, with a valid derived token, answer `401` where the pre-change behavior answered `404`. Not a security hole (no credential ever opens the write endpoint), but it is a behavior change on `/api/query` that the task's "must not change apart from which credential they accept" (task:187-188) does not license, and nothing pins it. Fix: one sentence — "paths under `/api/*` other than the two exact names are rejected with `401` for any credential" — plus one test row.

### 11. (WIP-specific, blocks by contradiction) The response contract for skipped rows depends on `connection.getWarnings()` during COPY, which the task itself had reservations about
- Task:46-47 ("Verify that pgjdbc surfaces the notice during `COPY`; if it does not, fall back ... and say in the report which way was used"); WIP `DataWriteController.java:446-454` reads `connection.getWarnings()` after `copyIn`/`endCopy`, filtered to messages starting with a digit, after an explicit `clearWarnings()`.
- The mechanism may work, but for the single-row test only; nothing verifies the count with several skipped rows, and nothing verifies that a *second, unrelated* notice present on the connection after `clearWarnings` cannot also start with a digit (spurious count). Fix: a test with more than one skipped row (exercises the plural sentence `"N rows were skipped due to data type incompatibility"`, confirmed from `copyfrom.c` — the notice carries the count first, so the leading-integer rule is sound), and state in the task that the count must come from the ON_ERROR notice specifically, not the first digit-bearing warning.

## Test gaps (cases the listed tests would not catch)

1. Header cell containing a quoted newline (multi-physical-line header) — validates server-parser/PG-skip agreement (finding 2).
2. Header cell with a mid-field quote — the dialect divergence made the WIP reject a valid file (finding 2).
3. Ragged row with **more** fields than columns (`extra data after last expected column`) — only the fewer-fields case is listed.
4. Two or more skipped rows — plural NOTICE form and the multi-count path (finding 11).
5. Zero-arg aggregate as `X-Function-Name` (finding 3).
6. JSON array containing a non-object element (`[1]`, `["a"]`, `[null]`) — the rule "every element must be an object" exists in the WIP but not in the task; it should be in the error table and tests both.
7. A function that drops the auto-created table during the call (finding 7).
8. A pooled session poisoned by `SET datestyle`/`SET client_encoding` via the `memory` tool right before a bulk load (finding 1).
9. `X-Access-Token` instead of `Authorization: Bearer` on `/api/data` — the filter accepts both spellings for every endpoint (`TokenAuthFilter.java:91-97`); decide whether the write endpoint accepts the alternate header spelling, or it stays an undocumented accident.

## What would block implementation

1. Findings 2 and 11: without the exact CSV dialect rule and the extra-row-notice vector, column derivation and `rows_ignored` are only accidentally correct — the WIP already rejects a well-formed file (finding 2).
2. Findings 1 and 4: the load can silently corrupt or silently discard correct data on pooled sessions; the fixes are two `SET LOCAL` lines and one removed guard, but the contract must say so.
3. Finding 3: `prokind IN ('f','p')` must be written into the task before the resolution SQL is trusted.
4. Finding 5: header-only CSV must be a decided case (400 as "empty") in both the contract text and the test list.
5. Finding 8: the upgrade note for deployed instances is a release requirement — without it the first restart of an updated deployment bricks the server until an operator intervenes.
