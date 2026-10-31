package se.rocketscien.mcp.springmemorymcpserver.api;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;

/**
 * SQLState class 08 is "connection exception", 53 is "insufficient resources" and 57P01/57P03 mean
 * that the server is shutting down: in all of them the request can be retried later (HTTP 503).
 */
final class SqlErrors {

    private SqlErrors() {
    }

    static boolean isConnectionFailure(Exception e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLTransientConnectionException || cause instanceof SQLNonTransientConnectionException) {
                return true;
            }
            if (cause instanceof SQLException sqlException) {
                var sqlState = sqlException.getSQLState();
                if (sqlState != null && (sqlState.startsWith("08") || sqlState.startsWith("53")
                        || sqlState.equals("57P01") || sqlState.equals("57P03"))) {
                    return true;
                }
            }
        }
        return false;
    }
}
