package com.poker.adminconsole;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

final class TablePrinter {
    private static final int MAX_CELL_WIDTH = 48;

    private TablePrinter() {
    }

    static int print(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        int columnCount = metadata.getColumnCount();
        List<String> headers = new ArrayList<>(columnCount);
        List<List<String>> rows = new ArrayList<>();

        for (int column = 1; column <= columnCount; column++) {
            headers.add(shorten(metadata.getColumnLabel(column)));
        }

        while (resultSet.next()) {
            List<String> row = new ArrayList<>(columnCount);
            for (int column = 1; column <= columnCount; column++) {
                row.add(formatValue(resultSet.getObject(column)));
            }
            rows.add(row);
        }

        print(headers, rows);
        return rows.size();
    }

    static void print(List<String> headers, List<List<String>> rows) {
        if (headers.isEmpty()) {
            System.out.println("(no columns)");
            return;
        }

        int[] widths = headers.stream().mapToInt(String::length).toArray();
        for (List<String> row : rows) {
            for (int column = 0; column < widths.length; column++) {
                widths[column] = Math.max(widths[column], row.get(column).length());
            }
        }

        String border = border(widths);
        System.out.println(border);
        printRow(headers, widths);
        System.out.println(border);
        for (List<String> row : rows) {
            printRow(row, widths);
        }
        System.out.println(border);
        System.out.printf("%d row%s%n", rows.size(), rows.size() == 1 ? "" : "s");
    }

    private static void printRow(List<String> row, int[] widths) {
        StringBuilder output = new StringBuilder("|");
        for (int column = 0; column < widths.length; column++) {
            output.append(' ')
                    .append(pad(row.get(column), widths[column]))
                    .append(" |");
        }
        System.out.println(output);
    }

    private static String border(int[] widths) {
        StringBuilder border = new StringBuilder("+");
        Arrays.stream(widths).forEach(width -> border.append("-".repeat(width + 2)).append('+'));
        return border.toString();
    }

    private static String pad(String value, int width) {
        return value + " ".repeat(Math.max(0, width - value.length()));
    }

    private static String formatValue(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof byte[] bytes) {
            return "<" + bytes.length + " bytes>";
        }
        return shorten(value.toString().replace("\r", "\\r").replace("\n", "\\n"));
    }

    private static String shorten(String value) {
        if (value.length() <= MAX_CELL_WIDTH) {
            return value;
        }
        return value.substring(0, MAX_CELL_WIDTH - 3) + "...";
    }
}
