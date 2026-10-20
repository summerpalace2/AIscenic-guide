package com.ai.guide.infrastructure.persistence.migration;

import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeSchemaScopeTest {
    @Test void postgresLocksBeforeFirstDdlAndRollsBackOnFailure() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getURL()).thenReturn("jdbc:postgresql://localhost:5432/test");
        when(connection.getAutoCommit()).thenReturn(true);
        JdbcTemplate jdbc = spy(new JdbcTemplate(source));
        doReturn(java.util.List.of()).when(jdbc).queryForList("SELECT pg_advisory_xact_lock(471932761)");
        IllegalStateException failure = new IllegalStateException("first DDL failed");
        doThrow(failure).when(jdbc).update(startsWith("CREATE TABLE"));

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> new KnowledgeSchemaInitializer().initialize(jdbc, false)));
        var order = inOrder(jdbc);
        order.verify(jdbc).queryForList("SELECT pg_advisory_xact_lock(471932761)");
        order.verify(jdbc).update(startsWith("CREATE TABLE"));
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    @Test void anotherPostgresSchemaCannotSatisfyARequiredColumn() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet columns = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getURL()).thenReturn("jdbc:postgresql://localhost:5432/test");
        when(connection.getSchema()).thenReturn("target_schema");
        when(metadata.getColumns(any(), any(), any(), any())).thenReturn(columns);
        when(columns.next()).thenReturn(true, false);
        when(columns.getString("TABLE_NAME")).thenReturn("fixture");
        when(columns.getString("COLUMN_NAME")).thenReturn("required_column");
        when(columns.getString("TABLE_SCHEM")).thenReturn("other_schema");
        Method method = KnowledgeSchemaInitializer.class.getDeclaredMethod(
                "columnExists", JdbcTemplate.class, String.class, String.class);
        method.setAccessible(true);
        assertEquals(false, method.invoke(new KnowledgeSchemaInitializer(), new JdbcTemplate(source),
                "fixture", "required_column"));
    }

    @Test void postgresIndexInspectionUsesTheCurrentSchema() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet indexes = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getURL()).thenReturn("jdbc:postgresql://localhost:5432/test");
        when(connection.getSchema()).thenReturn("target_schema");
        when(metadata.getIndexInfo(any(), any(), eq("fixture"), eq(false), eq(false))).thenReturn(indexes);
        when(indexes.next()).thenReturn(true, false);
        when(indexes.getString("INDEX_NAME")).thenReturn("fixture_index");
        when(indexes.getString("COLUMN_NAME")).thenReturn("id");
        when(indexes.getInt("ORDINAL_POSITION")).thenReturn(1);
        Method method = KnowledgeSchemaInitializer.class.getDeclaredMethod(
                "verifyIndex", JdbcTemplate.class, String.class, String.class, java.util.List.class);
        method.setAccessible(true);
        method.invoke(new KnowledgeSchemaInitializer(), new JdbcTemplate(source),
                "fixture", "fixture_index", java.util.List.of("id"));
        verify(metadata).getIndexInfo(null, "target_schema", "fixture", false, false);
    }

    @Test void failedPostgresAlterDoesNotRequeryAnAbortedTransaction() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet columns = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getURL()).thenReturn("jdbc:postgresql://localhost:5432/test");
        when(connection.getSchema()).thenReturn("target_schema");
        when(metadata.getColumns(any(), any(), any(), any())).thenReturn(columns);
        when(columns.next()).thenReturn(false);
        JdbcTemplate jdbc = spy(new JdbcTemplate(source));
        IllegalStateException failure = new IllegalStateException("ALTER permission denied");
        doThrow(failure).when(jdbc).update(startsWith("ALTER TABLE"));
        Method method = KnowledgeSchemaInitializer.class.getDeclaredMethod(
                "ensureColumn", JdbcTemplate.class, String.class, String.class);
        method.setAccessible(true);
        var wrapped = assertThrows(java.lang.reflect.InvocationTargetException.class,
                () -> method.invoke(new KnowledgeSchemaInitializer(), jdbc, "fixture", "new_column INTEGER"));
        assertSame(failure, wrapped.getCause().getCause());
        verify(metadata, times(1)).getColumns(any(), any(), any(), any());
    }
}
