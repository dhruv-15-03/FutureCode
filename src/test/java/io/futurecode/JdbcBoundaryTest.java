package io.futurecode;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class JdbcBoundaryTest {
    @Test void databaseFailuresDoNotReturnQueryValuesOrCredentials() {
        JdbcTemplate jdbc = new JdbcTemplate() {
            @Override public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) { throw new DataAccessResourceFailureException("PRIVATE-QUERY-PASSWORD"); }
        };
        JdbcStore store = new JdbcStore(jdbc, new TransactionTemplate(), TestData.settings(false));
        ApiError error = assertThrows(ApiError.class, () -> store.getProblem(UUID.randomUUID().toString()));
        assertEquals(503, error.status()); assertFalse(error.getMessage().contains("PRIVATE"));
    }
    @Test void exhaustedTransactionConnectionsAlsoReportStorageUnavailableRatherThanGenericSuccess() {
        TransactionTemplate transactions = new TransactionTemplate() {
            @Override public <T> T execute(TransactionCallback<T> action) { throw new CannotCreateTransactionException("PRIVATE-CONNECTION-CREDENTIAL"); }
        };
        JdbcStore store = new JdbcStore(new JdbcTemplate(), transactions, TestData.settings(false));
        ApiError error = assertThrows(ApiError.class, () -> store.upsertGoogleUser(new Store.Identity("sub", "a@b.test", "A")));
        assertEquals(503, error.status()); assertFalse(error.getMessage().contains("PRIVATE"));
    }
}
