package com.minigit.cli;

import com.minigit.core.RepoLock;
import com.minigit.exceptions.MiniGitException;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dispatches {@code argv} to the matching {@link Command} and turns exceptions into exit codes.
 * Expected failures print {@code fatal: <message>}; only genuine bugs produce a stack trace
 * (and only when {@code MINIGIT_DEBUG} is set).
 */
public class CommandHandler {

    public static final String VERSION = "1.0.0";
    public static final int EXIT_OK = 0;
    public static final int EXIT_FAILURE = 1;
    public static final int EXIT_INTERNAL_ERROR = 2;

    private static final String DEBUG_ENV = "MINIGIT_DEBUG";
    private static final String HELP = "help";

    private final Console console;
    private final Path workingDirectory;
    private final Map<String, Command> commands = new LinkedHashMap<>();

    public CommandHandler(Console console, Path workingDirectory, List<Command> commands) {
        this.console = console;
        this.workingDirectory = workingDirectory;
        commands.forEach(command -> this.commands.put(command.name(), command));
    }

    /** Runs one invocation and returns the process exit code. */
    public int run(String[] argv) {
        if (argv.length == 0) {
            runCommand(HELP, List.of());
            return EXIT_FAILURE;
        }
        String first = argv[0];
        List<String> rest = Arrays.asList(argv).subList(1, argv.length);
        if (first.equals("--version") || first.equals("-v") || first.equals("version")) {
            console.println("MiniGit version " + VERSION);
            return EXIT_OK;
        }
        if (first.equals("--help") || first.equals("-h")) {
            return runCommand(HELP, List.of());
        }
        if (!commands.containsKey(first)) {
            console.error("minigit: '" + first + "' is not a minigit command. See 'minigit help'.");
            return EXIT_FAILURE;
        }
        return runCommand(first, rest);
    }

    private int runCommand(String name, List<String> rawArgs) {
        Command command = commands.get(name);
        try {
            ParsedArgs args = CommandParser.parse(rawArgs, command.valueOptions());
            // Only a real flag counts: "-m -h" is a commit message, not a request for help.
            if (!name.equals(HELP) && args.hasFlag("--help", "-h")) {
                return runCommand(HELP, List.of(name));
            }
            CommandContext context = new CommandContext(workingDirectory, args, console);
            if (command.mutatesRepository()) {
                try (RepoLock ignored = RepoLock.acquire(context.repository())) {
                    command.execute(context);
                }
            } else {
                command.execute(context);
            }
            return EXIT_OK;
        } catch (MiniGitException e) {
            console.error("fatal: " + e.getMessage());
            return EXIT_FAILURE;
        } catch (RuntimeException e) {
            console.error("error: unexpected internal error: " + e);
            if (System.getenv(DEBUG_ENV) != null) {
                e.printStackTrace();
            } else {
                console.error("(set " + DEBUG_ENV + "=1 to see the stack trace)");
            }
            return EXIT_INTERNAL_ERROR;
        }
    }
}
