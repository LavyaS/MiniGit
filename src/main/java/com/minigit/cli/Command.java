package com.minigit.cli;

import java.util.Set;

/** One MiniGit sub-command such as {@code add} or {@code commit}. */
public interface Command {

    /** The word typed after {@code minigit}. */
    String name();

    /** One-line description shown in {@code minigit help}. */
    String summary();

    /** Full usage text shown by {@code minigit help <name>}. */
    String usage();

    /** Options that take a value (for example {@code -m}); everything else starting with '-' is a flag. */
    default Set<String> valueOptions() {
        return Set.of();
    }

    /** True for commands that modify the repository; the handler serialises them with a repository lock. */
    default boolean mutatesRepository() {
        return false;
    }

    /** Runs the command. Expected failures are reported by throwing a MiniGitException. */
    void execute(CommandContext context);
}
