package gov.nih.nhlbi.bdc.dataportal.cohortbuilder.load;

import java.sql.SQLException;

/**
 * No connection to the database could be obtained: it's down, unreachable, or refuses
 * the credentials or database name. The cause has the details.
 */
public class DatabaseUnavailableException extends SQLException {

    public DatabaseUnavailableException(String message, Throwable cause) {
        super(message, cause instanceof SQLException s ? s.getSQLState() : "08001", cause);
    }
}
