package se.rocketscien.mcp.springmemorymcpserver.tests.rest;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import se.rocketscien.mcp.springmemorymcpserver.tests.BaseCallToolTest;

import static org.assertj.core.api.Assertions.assertThat;

class DataWriteApiTest extends BaseCallToolTest {

    private static final String ALPHA_DB = "agent_alpha";

    @Test
    void csvLoadIntoTypedTableKeepsTypes() {
        memory(ALPHA_TOKEN, "CREATE TABLE typed (id int, price numeric, note text)");

        var response = postData(rwToken(ALPHA_TOKEN), "text/csv", "typed", null,
                "id,price,note\n1,2.50,hello\n2,3.75,world\n");

        assertThat(response.statusCode()).isEqualTo(200);
        var body = json(response.body());
        assertThat(body.get("rows_received").asLong()).isEqualTo(2);
        assertThat(body.get("rows_ignored").asLong()).isEqualTo(0);
        assertThat(body.get("rows_written").asLong()).isEqualTo(2);

        var sum = getResponseJson(memory(ALPHA_TOKEN, "SELECT sum(id) AS ids, sum(price) AS prices FROM typed"));
        assertThat(sum.get("rows").get(0).get("ids").asInt()).isEqualTo(3);
        assertThat(sum.get("rows").get(0).get("prices").asDouble()).isEqualTo(6.25);
    }

    @Test
    void csvLoadCreatesMissingTableWithTextColumns() {
        var response = postData(rwToken(ALPHA_TOKEN), "text/csv", "created", null, "a,b\nx,y\n");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response.body()).get("rows_written").asLong()).isEqualTo(1);

        var types = getResponseJson(memory(ALPHA_TOKEN,
                "SELECT data_type FROM information_schema.columns WHERE table_name = 'created' "
                        + "ORDER BY ordinal_position"));
        assertThat(types.get("rows").get(0).get("data_type").asText()).isEqualTo("text");
        assertThat(types.get("rows").get(1).get("data_type").asText()).isEqualTo("text");
        assertThat(rowCount(ALPHA_DB, "created")).isEqualTo(1);
    }

    @Test
    void jsonLoadUsesFirstObjectKeysAndKeepsNestedValueAsJson() {
        var response = postData(rwToken(ALPHA_TOKEN), "application/json", "json_load", null,
                """
                        [{"id": 1, "nested": {"x": [1, 2]}, "tag": "a,b"},
                         {"id": 2, "tag": "plain"},
                         {"id": 3, "nested": null}]
                        """);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response.body()).get("rows_written").asLong()).isEqualTo(3);

        var rows = getResponseJson(memory(ALPHA_TOKEN,
                "SELECT id, nested, tag FROM json_load ORDER BY id"));
        assertThat(rows.get("rows").get(0).get("nested").asText()).isEqualTo("{\"x\":[1,2]}");
        assertThat(rows.get("rows").get(0).get("tag").asText()).isEqualTo("a,b");
        assertThat(rows.get("rows").get(1).get("nested").isNull()).isTrue();
    }

    @Test
    void conversionFailuresAreSkippedAndCounted() {
        memory(ALPHA_TOKEN, "CREATE TABLE conversion (n int, s text)");

        var response = postData(rwToken(ALPHA_TOKEN), "text/csv", "conversion", null,
                "n,s\n1,ok\nnot-an-int,bad\n3,ok\nalso-bad,no\n5,ok\n6.6,nope\n");

        assertThat(response.statusCode()).isEqualTo(200);
        var body = json(response.body());
        assertThat(body.get("rows_written").asLong()).isEqualTo(3);
        assertThat(body.get("rows_ignored").asLong()).isEqualTo(3);
        assertThat(body.get("rows_received").asLong()).isEqualTo(6);
        assertThat(rowCount(ALPHA_DB, "conversion")).isEqualTo(3);
    }

    @Test
    void jsonConversionFailuresAreCountedToo() {
        memory(ALPHA_TOKEN, "CREATE TABLE json_conversion (n int, s text)");

        var response = postData(rwToken(ALPHA_TOKEN), "application/json", "json_conversion", null,
                """
                        [{"n": 1, "s": "ok"},
                         {"n": "not-an-int", "s": "bad"},
                         {"n": 3, "s": "ok"},
                         {"n": "also-bad", "s": "no"}]
                        """);

        assertThat(response.statusCode()).isEqualTo(200);
        var body = json(response.body());
        assertThat(body.get("rows_received").asLong()).isEqualTo(4);
        assertThat(body.get("rows_written").asLong()).isEqualTo(2);
        assertThat(body.get("rows_ignored").asLong()).isEqualTo(2);
    }

    @Test
    void quotedNewlineInDataCountsAsOneRecord() {
        memory(ALPHA_TOKEN, "CREATE TABLE multiline (a text, b text)");

        var response = postData(rwToken(ALPHA_TOKEN), "text/csv", "multiline", null,
                "a,b\n\"line1\nline2\",x\n");

        assertThat(response.statusCode()).isEqualTo(200);
        var body = json(response.body());
        assertThat(body.get("rows_received").asLong()).isEqualTo(1);
        assertThat(body.get("rows_written").asLong()).isEqualTo(1);
        assertThat(body.get("rows_ignored").asLong()).isEqualTo(0);
        var rows = getResponseJson(memory(ALPHA_TOKEN, "SELECT a FROM multiline"));
        assertThat(rows.get("rows").get(0).get("a").asText()).isEqualTo("line1\nline2");
    }

    @Test
    void crlfBodyCountsEachRecordOnce() {
        memory(ALPHA_TOKEN, "CREATE TABLE crlf (n int, s text)");

        var response = postData(rwToken(ALPHA_TOKEN), "text/csv", "crlf", null,
                "n,s\r\n1,ok\r\nnot-an-int,bad\r\n3,ok\r\n");

        assertThat(response.statusCode()).isEqualTo(200);
        var body = json(response.body());
        assertThat(body.get("rows_received").asLong()).isEqualTo(3);
        assertThat(body.get("rows_written").asLong()).isEqualTo(2);
        assertThat(body.get("rows_ignored").asLong()).isEqualTo(1);
        assertThat(rowCount(ALPHA_DB, "crlf")).isEqualTo(2);
    }

    @Test
    void utf8BomIsStrippedFromTheCsvHeader() {
        memory(ALPHA_TOKEN, "CREATE TABLE bom_typed (n int, s text)");

        var response = postData(rwToken(ALPHA_TOKEN), "text/csv", "bom_typed", null, "\uFEFFn,s\n1,ok\n");

        assertThat(response.statusCode()).isEqualTo(200);
        var body = json(response.body());
        assertThat(body.get("rows_received").asLong()).isEqualTo(1);
        assertThat(body.get("rows_written").asLong()).isEqualTo(1);
        assertThat(rowCount(ALPHA_DB, "bom_typed")).isEqualTo(1);
        var rows = getResponseJson(memory(ALPHA_TOKEN, "SELECT n FROM bom_typed"));
        assertThat(rows.get("rows").get(0).get("n").asInt()).isEqualTo(1);
    }

    @Test
    void functionReceivesLoadedRowsAndReturnsResult() {
        memory(ALPHA_TOKEN, "CREATE TABLE report_rows (v text)");
        memory(ALPHA_TOKEN, """
                CREATE FUNCTION report() RETURNS TABLE(total bigint) AS $$
                BEGIN
                    RETURN QUERY SELECT count(*) FROM report_rows;
                END
                $$ LANGUAGE plpgsql
                """);

        var response = postData(rwToken(ALPHA_TOKEN), "text/csv", "report_rows", "report", "v\na\nb\n");

        assertThat(response.statusCode()).isEqualTo(200);
        var body = json(response.body());
        assertThat(body.get("rows_written").asLong()).isEqualTo(2);
        assertThat(body.get("result").get(0).get("total").asLong()).isEqualTo(2);
    }

    @Test
    void functionInAnotherSchemaIsCalled() {
        memory(ALPHA_TOKEN, "CREATE SCHEMA report");
        memory(ALPHA_TOKEN, "CREATE TABLE report_rows (v text)");
        memory(ALPHA_TOKEN, """
                CREATE FUNCTION report.count_rows() RETURNS TABLE(total bigint) AS $$
                BEGIN
                    RETURN QUERY SELECT count(*) FROM report_rows;
                END
                $$ LANGUAGE plpgsql
                """);

        var response = postData(rwToken(ALPHA_TOKEN), "text/csv", "report_rows", "report.count_rows", "v\na\n");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response.body()).get("result").get(0).get("total").asLong()).isEqualTo(1);
    }

    @Test
    void procedureIsCalledAndReturnsNoResult() {
        memory(ALPHA_TOKEN, "CREATE TABLE proc_rows (v text)");
        memory(ALPHA_TOKEN, "CREATE TABLE proc_copy (v text)");
        memory(ALPHA_TOKEN, """
                CREATE PROCEDURE copy_rows() AS $$
                BEGIN
                    INSERT INTO proc_copy SELECT v FROM proc_rows;
                END
                $$ LANGUAGE plpgsql
                """);

        var response = postData(rwToken(ALPHA_TOKEN), "text/csv", "proc_rows", "copy_rows", "v\na\n");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response.body()).get("result").isNull()).isTrue();
        assertThat(rowCount(ALPHA_DB, "proc_copy")).isEqualTo(1);
    }

    @Test
    void readTokenAndRawUuidAreRejectedOnData() {
        assertThat(postData(roToken(ALPHA_TOKEN), "text/csv", "t", null, "a\n1\n").statusCode()).isEqualTo(401);
        assertThat(postData(ALPHA_TOKEN, "text/csv", "t", null, "a\n1\n").statusCode()).isEqualTo(401);
    }

    @Test
    void invalidTableNameIsRejected() {
        assertThat(postData(rwToken(ALPHA_TOKEN), "text/csv", "bad name", null, "a\n1\n").statusCode())
                .isEqualTo(400);
        assertThat(postData(rwToken(ALPHA_TOKEN), "text/csv", "a.b.c", null, "a\n1\n").statusCode())
                .isEqualTo(400);
        assertThat(postData(rwToken(ALPHA_TOKEN), "text/csv", "Public.T", null, "a\n1\n").statusCode())
                .isEqualTo(400);
        assertThat(postData(rwToken(ALPHA_TOKEN), "text/csv", "t;drop table x", null, "a\n1\n").statusCode())
                .isEqualTo(400);
    }

    @Test
    void invalidFunctionNameIsRejected() {
        assertThat(postData(rwToken(ALPHA_TOKEN), "text/csv", "t", "f()", "a\n1\n").statusCode()).isEqualTo(400);
        assertThat(postData(rwToken(ALPHA_TOKEN), "text/csv", "t", "f; DROP TABLE x", "a\n1\n").statusCode())
                .isEqualTo(400);
    }

    @Test
    void functionThatRaisesRollsBackTheLoad() {
        memory(ALPHA_TOKEN, "CREATE TABLE boom_rows (v text)");
        memory(ALPHA_TOKEN, """
                CREATE FUNCTION boom() RETURNS void AS $$
                BEGIN
                    RAISE EXCEPTION 'boom';
                END
                $$ LANGUAGE plpgsql
                """);

        var response = postData(rwToken(ALPHA_TOKEN), "text/csv", "boom_rows", "boom", "v\na\n");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response.body()).get("error").asText()).contains("boom");
        assertThat(rowCount(ALPHA_DB, "boom_rows")).isZero();
    }

    @Test
    void functionThatRaisesLeavesNoAutoCreatedTable() {
        memory(ALPHA_TOKEN, """
                CREATE FUNCTION fail_after() RETURNS void AS $$
                BEGIN
                    RAISE EXCEPTION 'nope';
                END
                $$ LANGUAGE plpgsql
                """);

        var response = postData(rwToken(ALPHA_TOKEN), "text/csv", "auto_fail", "fail_after", "a\n1\n");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(table("auto_fail")).isNull();
    }

    @Test
    void columnDefaultIsKeptWhenTheColumnIsMissingFromTheInput() {
        memory(ALPHA_TOKEN, "CREATE TABLE defaulted (id int DEFAULT 7, v text)");

        var response = postData(rwToken(ALPHA_TOKEN), "text/csv", "defaulted", null, "v\nhello\n");

        assertThat(response.statusCode()).isEqualTo(200);
        var rows = getResponseJson(memory(ALPHA_TOKEN, "SELECT id, v FROM defaulted"));
        assertThat(rows.get("rows").get(0).get("id").asInt()).isEqualTo(7);
        assertThat(rows.get("rows").get(0).get("v").asText()).isEqualTo("hello");
    }

    @Test
    void autoCreatedTableIsDroppedAfterFunctionButKeptWithout() {
        memory(ALPHA_TOKEN, """
                CREATE FUNCTION consume() RETURNS TABLE(n bigint) AS $$
                BEGIN
                    RETURN QUERY SELECT count(*) FROM autocreate;
                END
                $$ LANGUAGE plpgsql
                """);

        var consumed = postData(rwToken(ALPHA_TOKEN), "text/csv", "autocreate", "consume", "a,b\n1,2\n");
        assertThat(consumed.statusCode()).isEqualTo(200);
        assertThat(json(consumed.body()).get("result").get(0).get("n").asLong()).isEqualTo(1);
        assertThat(table("autocreate")).isNull();

        var kept = postData(rwToken(ALPHA_TOKEN), "text/csv", "kept", null, "a\n1\n");
        assertThat(kept.statusCode()).isEqualTo(200);
        assertThat(table("kept")).isEqualTo("kept");
        assertThat(rowCount(ALPHA_DB, "kept")).isEqualTo(1);
    }

    @Test
    void unqualifiedTargetLandsInPublic() {
        var response = postData(rwToken(ALPHA_TOKEN), "text/csv", "plain_table", null, "a\n1\n");

        assertThat(response.statusCode()).isEqualTo(200);
        var schema = getResponseJson(memory(ALPHA_TOKEN,
                "SELECT table_schema FROM information_schema.tables WHERE table_name = 'plain_table'"));
        assertThat(schema.get("rows").get(0).get("table_schema").asText()).isEqualTo("public");
    }

    @Test
    void reservedWordTableNameWorks() {
        var response = postData(rwToken(ALPHA_TOKEN), "text/csv", "order", null, "id,total\n1,10\n");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(rowCount(ALPHA_DB, "\"order\"")).isEqualTo(1);
    }

    @Test
    void quotedCsvHeaderCellWithCommaIsParsed() {
        var response = postData(rwToken(ALPHA_TOKEN), "text/csv", "quoted_header", null,
                "\"a,b\",c\n\"x,y\",z\n");

        assertThat(response.statusCode()).isEqualTo(200);
        var rows = getResponseJson(memory(ALPHA_TOKEN, "SELECT \"a,b\", c FROM quoted_header"));
        assertThat(rows.get("rows").get(0).get("a,b").asText()).isEqualTo("x,y");
        assertThat(rows.get("rows").get(0).get("c").asText()).isEqualTo("z");
    }

    /**
     * PostgreSQL's CSV input joins quoted parts even when the quote starts mid-field: {@code a,b"c",d}
     * is three columns {@code a}, {@code bc}, {@code d} (verified against PostgreSQL 18).
     */
    @Test
    void quoteInsideCsvHeaderCellFollowsPostgresCsvRule() {
        var response = postData(rwToken(ALPHA_TOKEN), "text/csv", "mid_quote", null, "a,b\"c\",d\n1,2,3\n");

        assertThat(response.statusCode()).isEqualTo(200);
        var rows = getResponseJson(memory(ALPHA_TOKEN, "SELECT * FROM mid_quote")).get("rows").get(0);
        assertThat(rows.get("a").asText()).isEqualTo("1");
        assertThat(rows.get("bc").asText()).isEqualTo("2");
        assertThat(rows.get("d").asText()).isEqualTo("3");
    }

    @Test
    void doubledQuoteInCsvHeaderCellIsALiteralQuote() {
        var response = postData(rwToken(ALPHA_TOKEN), "text/csv", "doubled_quote", null, "\"b\"\"c\",d\n2,3\n");

        assertThat(response.statusCode()).isEqualTo(200);
        var rows = getResponseJson(memory(ALPHA_TOKEN, "SELECT * FROM doubled_quote")).get("rows").get(0);
        assertThat(rows.get("b\"c").asText()).isEqualTo("2");
        assertThat(rows.get("d").asText()).isEqualTo("3");
    }

    @Test
    void quotedNewlineInCsvHeaderIsPartOfTheHeaderRecord() {
        var response = postData(rwToken(ALPHA_TOKEN), "text/csv", "newline_header", null, "\"a\nb\",c\n1,2\n");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(rowCount(ALPHA_DB, "newline_header")).isEqualTo(1);
        var rows = getResponseJson(memory(ALPHA_TOKEN, "SELECT * FROM newline_header")).get("rows").get(0);
        assertThat(rows.get("a\nb").asText()).isEqualTo("1");
        assertThat(rows.get("c").asText()).isEqualTo("2");
    }

    @Test
    void raggedCsvRowIsBadRequestAndNothingLands() {
        memory(ALPHA_TOKEN, "CREATE TABLE ragged (a text, b text)");

        var response = postData(rwToken(ALPHA_TOKEN), "text/csv", "ragged", null, "a,b\n1\n");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(rowCount(ALPHA_DB, "ragged")).isZero();
    }

    @Test
    void unknownColumnInExistingTableIsBadRequest() {
        memory(ALPHA_TOKEN, "CREATE TABLE known (a text)");

        var response = postData(rwToken(ALPHA_TOKEN), "text/csv", "known", null, "a,b\n1,2\n");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(rowCount(ALPHA_DB, "known")).isZero();
    }

    @Test
    void zeroColumnsAreBadRequest() {
        assertThat(postData(rwToken(ALPHA_TOKEN), "text/csv", "empty_body", null, "").statusCode())
                .isEqualTo(400);
        assertThat(postData(rwToken(ALPHA_TOKEN), "text/csv", "header_only", null, "a,b\n").statusCode())
                .isEqualTo(400);
        assertThat(postData(rwToken(ALPHA_TOKEN), "application/json", "empty_array", null, "[]").statusCode())
                .isEqualTo(400);
        assertThat(postData(rwToken(ALPHA_TOKEN), "application/json", "empty_object", null, "{}").statusCode())
                .isEqualTo(400);
        assertThat(postData(rwToken(ALPHA_TOKEN), "application/json", "empty_row", null, "[{}]").statusCode())
                .isEqualTo(400);
    }

    @Test
    void bareJsonObjectIsBadRequest() {
        assertThat(postData(rwToken(ALPHA_TOKEN), "application/json", "bare", null, "{\"a\": 1}").statusCode())
                .isEqualTo(400);
    }

    @Test
    void duplicateColumnsAreBadRequest() {
        assertThat(postData(rwToken(ALPHA_TOKEN), "text/csv", "dup_csv", null, "a,a\n1,2\n").statusCode())
                .isEqualTo(400);
        assertThat(postData(rwToken(ALPHA_TOKEN), "application/json", "dup_json", null,
                "[{\"a\": 1, \"a\": 2}]").statusCode())
                .isEqualTo(400);
    }

    @Test
    void emptyColumnNameIsBadRequest() {
        assertThat(postData(rwToken(ALPHA_TOKEN), "text/csv", "empty_column", null, "a,,c\n1,2,3\n").statusCode())
                .isEqualTo(400);
        assertThat(postData(rwToken(ALPHA_TOKEN), "text/csv", "blank_header", null, "\n1\n").statusCode())
                .isEqualTo(400);
    }

    @Test
    void columnNameLongerThan63BytesIsBadRequest() {
        var longName = "c".repeat(64);
        var response = postData(rwToken(ALPHA_TOKEN), "application/json", "long_column", null,
                "[{\"" + longName + "\": 1}]");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(table("long_column")).isNull();
    }

    @Test
    void datestyleLeftOnAPooledConnectionDoesNotChangeDateParsing() {
        memory(ALPHA_TOKEN, "SET datestyle = 'german'");
        memory(ALPHA_TOKEN, "CREATE TABLE dated (d date)");

        // 24/01/31 is 2024-01-31 only under ISO, YMD; German reads it as 2031-01-24, MDY rejects it.
        var response = postData(rwToken(ALPHA_TOKEN), "text/csv", "dated", null, "d\n24/01/31\n");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(json(response.body()).get("rows_written").asLong()).isEqualTo(1);
        var rows = getResponseJson(memory(ALPHA_TOKEN, "SELECT d::text AS d FROM dated"));
        assertThat(rows.get("rows").get(0).get("d").asText()).isEqualTo("2024-01-31");

        memory(ALPHA_TOKEN, "SET datestyle = 'ISO, MDY'");
    }

    @Test
    void unknownJsonKeyIsBadRequest() {
        var response = postData(rwToken(ALPHA_TOKEN), "application/json", "json_unknown", null,
                "[{\"a\": 1}, {\"a\": 2, \"b\": 3}]");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(table("json_unknown")).isNull();
    }

    @Test
    void functionNotFoundOrTakingArgumentsIsBadRequest() {
        memory(ALPHA_TOKEN, "CREATE FUNCTION needs_arg(x int) RETURNS int AS $$ SELECT x $$ LANGUAGE sql");

        assertThat(postData(rwToken(ALPHA_TOKEN), "text/csv", "f1", "no_such_function", "a\n1\n").statusCode())
                .isEqualTo(400);
        assertThat(postData(rwToken(ALPHA_TOKEN), "text/csv", "f2", "needs_arg", "a\n1\n").statusCode())
                .isEqualTo(400);
    }

    @Test
    void aggregateIsNotACallableFunction() {
        var response = postData(rwToken(ALPHA_TOKEN), "text/csv", "agg", "pg_catalog.count", "a\n1\n");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json(response.body()).get("error").asText()).contains("Not a callable function");
    }

    @Test
    void accessTokenHeaderIsAccepted() throws Exception {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(dataUrl()))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "text/csv")
                .header("X-Access-Token", rwToken(ALPHA_TOKEN))
                .header("X-Target-Table", "header_token")
                .POST(HttpRequest.BodyPublishers.ofString("a\n1\n"))
                .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void corsIsOpenOnQueryButClosedOnData() throws Exception {
        assertThat(preflight(apiUrl()).headers().firstValue("Access-Control-Allow-Origin")).isPresent();
        assertThat(preflight(dataUrl()).headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void contentTypeParametersAreIgnored() {
        var response = postData(rwToken(ALPHA_TOKEN), "text/csv; charset=utf-8", "charset", null, "a\n1\n");

        assertThat(response.statusCode()).isEqualTo(200);
    }

    @Test
    void unsupportedContentTypeIsBadRequest() {
        assertThat(postData(rwToken(ALPHA_TOKEN), "text/plain", "plain", null, "a\n1\n").statusCode())
                .isEqualTo(400);
    }

    @Test
    void missingTokenIsUnauthorized() {
        assertThat(postData(null, "text/csv", "t", null, "a\n1\n").statusCode()).isEqualTo(401);
    }

    /** The table name as the server sees it, or null when the table does not exist. */
    @SneakyThrows
    private String table(String name) {
        var json = getResponseJson(memory(ALPHA_TOKEN, "SELECT to_regclass('public." + name + "')::text AS t"));
        var value = json.get("rows").get(0).get("t");
        return value.isNull() ? null : value.asText();
    }

    @SneakyThrows
    private JsonNode json(String body) {
        return objectMapper.readTree(body);
    }

    @SneakyThrows
    private HttpResponse<String> postData(String token, String contentType, String table, String function,
                                          String body) {
        var builder = HttpRequest.newBuilder()
                .uri(URI.create(dataUrl()))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body));

        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        if (table != null) {
            builder.header("X-Target-Table", table);
        }
        if (function != null) {
            builder.header("X-Function-Name", function);
        }

        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    @SneakyThrows
    private HttpResponse<String> preflight(String url) {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Origin", "http://example.com")
                .header("Access-Control-Request-Method", "POST")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .build();

        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
