package com.poker.adminconsole;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class DatabaseBrowser {
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 500;

    private static final Pattern READ_QUERY = Pattern.compile(
            "^(SELECT|WITH|VALUES|TABLE|EXPLAIN|SHOW)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern FOR_UPDATE = Pattern.compile("\\bFOR\\s+UPDATE\\b", Pattern.CASE_INSENSITIVE);

    private final Connection connection;

    DatabaseBrowser(Connection connection) {
        this.connection = connection;
    }

    void listTables() throws SQLException {
        List<String> headers = List.of("SCHEMA", "TABLE", "TYPE", "ROWS");
        List<List<String>> rows = new ArrayList<>();

        try (ResultSet resultSet = connection.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
            while (resultSet.next()) {
                String schema = resultSet.getString("TABLE_SCHEM");
                if ("INFORMATION_SCHEMA".equalsIgnoreCase(schema)) {
                    continue;
                }
                String table = resultSet.getString("TABLE_NAME");
                rows.add(List.of(schema, table, resultSet.getString("TABLE_TYPE"), countRows(schema, table)));
            }
        }

        TablePrinter.print(headers, rows);
    }

    void describe(String requestedTable) throws SQLException {
        TableRef table = resolveTable(requestedTable);
        Set<String> primaryKeys = primaryKeys(table);
        List<String> headers = List.of("COLUMN", "TYPE", "NULLABLE", "DEFAULT", "PRIMARY KEY");
        List<List<String>> rows = new ArrayList<>();

        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet columns = metadata.getColumns(null, table.schema(), table.name(), "%")) {
            while (columns.next()) {
                String columnName = columns.getString("COLUMN_NAME");
                String size = columns.getString("COLUMN_SIZE");
                String scale = columns.getString("DECIMAL_DIGITS");
                String type = columns.getString("TYPE_NAME") + typeSize(size, scale);
                rows.add(List.of(
                        columnName,
                        type,
                        columns.getInt("NULLABLE") == DatabaseMetaData.columnNoNulls ? "NO" : "YES",
                        nullable(columns.getString("COLUMN_DEF")),
                        primaryKeys.contains(columnName.toUpperCase(Locale.ROOT)) ? "YES" : ""
                ));
            }
        }

        System.out.println(table.displayName());
        TablePrinter.print(headers, rows);
    }

    void showRows(String requestedTable, int limit) throws SQLException {
        TableRef table = resolveTable(requestedTable);
        int safeLimit = checkedLimit(limit);
        String sql = "SELECT * FROM " + table.quotedName() + " LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, safeLimit);
            try (ResultSet resultSet = statement.executeQuery()) {
                System.out.println(table.displayName() + " (up to " + safeLimit + " rows)");
                TablePrinter.print(resultSet);
            }
        } finally {
            connection.rollback();
        }
    }

    void count(String requestedTable) throws SQLException {
        TableRef table = resolveTable(requestedTable);
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) AS ROWS FROM " + table.quotedName())) {
            TablePrinter.print(resultSet);
        } finally {
            connection.rollback();
        }
    }

    void runQuery(String rawSql) throws SQLException {
        String sql = validateReadQuery(rawSql);
        try (Statement statement = connection.createStatement()) {
            statement.setMaxRows(MAX_LIMIT);
            statement.setQueryTimeout(30);
            try (ResultSet resultSet = statement.executeQuery(sql)) {
                int count = TablePrinter.print(resultSet);
                if (count == MAX_LIMIT) {
                    System.out.println("Output stopped at the " + MAX_LIMIT + "-row safety limit.");
                }
            }
        } finally {
            connection.rollback();
        }
    }

    int tableCount() throws SQLException {
        int count = 0;
        try (ResultSet tables = connection.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                if (!"INFORMATION_SCHEMA".equalsIgnoreCase(tables.getString("TABLE_SCHEM"))) {
                    count++;
                }
            }
        }
        return count;
    }

    private String countRows(String schema, String table) {
        String sql = "SELECT COUNT(*) FROM " + quote(schema) + "." + quote(table);
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return Long.toString(resultSet.getLong(1));
        } catch (SQLException ignored) {
            return "?";
        }
    }

    private Set<String> primaryKeys(TableRef table) throws SQLException {
        Set<String> keys = new HashSet<>();
        try (ResultSet primaryKeys = connection.getMetaData().getPrimaryKeys(null, table.schema(), table.name())) {
            while (primaryKeys.next()) {
                keys.add(primaryKeys.getString("COLUMN_NAME").toUpperCase(Locale.ROOT));
            }
        }
        return keys;
    }

    private TableRef resolveTable(String requestedTable) throws SQLException {
        String requestedSchema = null;
        String requestedName = requestedTable;
        int separator = requestedTable.indexOf('.');
        if (separator >= 0) {
            requestedSchema = requestedTable.substring(0, separator);
            requestedName = requestedTable.substring(separator + 1);
        }

        List<TableRef> matches = new ArrayList<>();
        try (ResultSet tables = connection.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String schema = tables.getString("TABLE_SCHEM");
                String name = tables.getString("TABLE_NAME");
                if (!"INFORMATION_SCHEMA".equalsIgnoreCase(schema)
                        && name.equalsIgnoreCase(requestedName)
                        && (requestedSchema == null || schema.equalsIgnoreCase(requestedSchema))) {
                    matches.add(new TableRef(schema, name));
                }
            }
        }

        if (matches.isEmpty()) {
            throw new IllegalArgumentException("Unknown table: " + requestedTable + ". Run 'tables' to see valid names.");
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("Ambiguous table name: " + requestedTable + ". Use schema.table.");
        }
        return matches.getFirst();
    }

    private static String validateReadQuery(String rawSql) {
        String sql = rawSql.trim();
        if (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        if (sql.isBlank() || !READ_QUERY.matcher(sql).find()) {
            throw new IllegalArgumentException("Only read-only SELECT, WITH, VALUES, TABLE, EXPLAIN, and SHOW queries are allowed.");
        }
        if (sql.contains(";") || FOR_UPDATE.matcher(sql).find()) {
            throw new IllegalArgumentException("Multiple statements and SELECT ... FOR UPDATE are not allowed.");
        }
        return sql;
    }

    private static int checkedLimit(int limit) {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("Limit must be between 1 and " + MAX_LIMIT + ".");
        }
        return limit;
    }

    private static String typeSize(String size, String scale) {
        if (size == null || "0".equals(size)) {
            return "";
        }
        return scale == null || "0".equals(scale) ? "(" + size + ")" : "(" + size + "," + scale + ")";
    }

    private static String nullable(String value) {
        return value == null ? "NULL" : value;
    }

    private static String quote(String identifier) {
        return '"' + identifier.replace("\"", "\"\"") + '"';
    }

    private record TableRef(String schema, String name) {
        String quotedName() {
            return quote(schema) + "." + quote(name);
        }

        String displayName() {
            return schema + "." + name;
        }
    }
}
