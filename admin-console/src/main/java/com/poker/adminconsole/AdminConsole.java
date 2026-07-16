package com.poker.adminconsole;

import java.io.Console;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Scanner;

public final class AdminConsole {
    private static final String HELP = """
            Database commands:
              tables                    List application tables and row counts
              describe <table>          Show a table's columns and primary key
              rows <table> [limit]      Browse rows (default 50, maximum 500)
              count <table>             Count rows in one table
              sql <query>               Run a read-only query (maximum 500 rows)

            Live server commands (need the game server running, e.g. npm run dev):
              live                      Show a live server overview
              rooms                     List live rooms, including private tables
              room <room>               Inspect one live room's members and game
              hand <room>               Live hand: board, pots, hole cards, action
              hand <room> <hand-id>     One hand's full event log, card by card
              hands <room>              Recent hands with boards, pots, winners
              sockets                   List connected users and socket counts

            <room> is a room id or join code. Hand views show every dealt card.

            Other:
              clear                     Clear the terminal
              help                      Show this help
              quit                      Exit Admin Console

            Table names are case-insensitive. Use schema.table if needed.
            """;

    private AdminConsole() {
    }

    public static void main(String[] args) {
        AdminConfig config = AdminConfig.fromEnvironment();
        LiveCommands live = new LiveCommands(LiveServerClient.fromEnvironment());

        try {
            Class.forName("org.h2.Driver");
            try (Connection connection = DriverManager.getConnection(config.url(), config.username(), config.password())) {
                connection.setAutoCommit(false);
                connection.setReadOnly(true);
                DatabaseBrowser browser = new DatabaseBrowser(connection);
                printBanner(config, live, browser.tableCount());

                if (args.length > 0) {
                    execute(browser, live, String.join(" ", args));
                    return;
                }
                interactiveLoop(browser, live);
            }
        } catch (ClassNotFoundException exception) {
            fail("The H2 driver is missing. Rebuild Admin Console with run.cmd.");
        } catch (SQLException exception) {
            fail("Could not open the database: " + exception.getMessage());
        } catch (IllegalArgumentException | LiveServerException exception) {
            fail(exception.getMessage());
        }
    }

    private static void interactiveLoop(DatabaseBrowser browser, LiveCommands live) {
        Console console = System.console();
        Scanner scanner = console == null ? new Scanner(System.in) : null;
        System.out.println(HELP);

        while (true) {
            String line;
            if (console != null) {
                line = console.readLine("admin> ");
            } else {
                System.out.print("admin> ");
                line = scanner.hasNextLine() ? scanner.nextLine() : null;
            }

            if (line == null || line.trim().equalsIgnoreCase("quit") || line.trim().equalsIgnoreCase("exit")) {
                System.out.println("Goodbye.");
                return;
            }
            if (line.isBlank()) {
                continue;
            }

            try {
                execute(browser, live, line);
            } catch (SQLException | IllegalArgumentException | LiveServerException exception) {
                System.err.println("Error: " + exception.getMessage());
            }
        }
    }

    private static void execute(DatabaseBrowser browser, LiveCommands live, String input) throws SQLException {
        String trimmed = input.trim();
        int separator = trimmed.indexOf(' ');
        String command = (separator < 0 ? trimmed : trimmed.substring(0, separator)).toLowerCase(Locale.ROOT);
        String remainder = separator < 0 ? "" : trimmed.substring(separator + 1).trim();

        switch (command) {
            case "tables" -> requireNoArguments(command, remainder, browser::listTables);
            case "describe", "desc" -> browser.describe(requireArgument(command, remainder));
            case "rows" -> showRows(browser, remainder);
            case "count" -> browser.count(requireArgument(command, remainder));
            case "sql" -> browser.runQuery(requireArgument(command, remainder));
            case "live" -> requireNoArguments(command, remainder, live::overview);
            case "rooms" -> requireNoArguments(command, remainder, live::rooms);
            case "room" -> live.room(requireArgument(command, remainder));
            case "hand" -> showHand(live, remainder);
            case "hands" -> live.hands(requireArgument(command, remainder));
            case "sockets", "connections" -> requireNoArguments(command, remainder, live::sockets);
            case "help", "?" -> System.out.println(HELP);
            case "clear", "cls" -> clearScreen();
            default -> throw new IllegalArgumentException("Unknown command: " + command + ". Run 'help' for available commands.");
        }
    }

    private static void showRows(DatabaseBrowser browser, String remainder) throws SQLException {
        String[] arguments = remainder.isBlank() ? new String[0] : remainder.split("\\s+");
        if (arguments.length < 1 || arguments.length > 2) {
            throw new IllegalArgumentException("Usage: rows <table> [limit]");
        }
        int limit = DatabaseBrowser.DEFAULT_LIMIT;
        if (arguments.length == 2) {
            try {
                limit = Integer.parseInt(arguments[1]);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Row limit must be a number.");
            }
        }
        browser.showRows(arguments[0], limit);
    }

    private static void showHand(LiveCommands live, String remainder) {
        String[] arguments = remainder.isBlank() ? new String[0] : remainder.split("\\s+");
        if (arguments.length == 1) {
            live.liveHand(arguments[0]);
            return;
        }
        if (arguments.length == 2) {
            try {
                live.handDetail(arguments[0], Long.parseLong(arguments[1]));
                return;
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("The hand id must be a number.");
            }
        }
        throw new IllegalArgumentException("Usage: hand <room> [hand-id]");
    }

    private static String requireArgument(String command, String argument) {
        if (argument.isBlank()) {
            throw new IllegalArgumentException("Usage: " + command + " <value>");
        }
        return argument;
    }

    private static void requireNoArguments(String command, String argument, SqlAction action) throws SQLException {
        if (!argument.isBlank()) {
            throw new IllegalArgumentException("Usage: " + command);
        }
        action.run();
    }

    private static void printBanner(AdminConfig config, LiveCommands live, int tableCount) {
        String target = config.url().replaceAll(";.*$", "");
        System.out.println("============================================================");
        System.out.println("  ADMIN CONSOLE  |  Read-only database and live-state browser");
        System.out.println("============================================================");
        System.out.println("Database:  " + target);
        System.out.println("User: " + config.username() + " | Tables: " + tableCount + " | Mode: READ ONLY");
        System.out.println("Live API:  " + live.status());
        System.out.println();
    }

    private static void clearScreen() {
        System.out.print("\033[H\033[2J");
        System.out.flush();
    }

    private static void fail(String message) {
        System.err.println("Admin Console: " + message);
        System.exit(1);
    }

    @FunctionalInterface
    private interface SqlAction {
        void run() throws SQLException;
    }
}
