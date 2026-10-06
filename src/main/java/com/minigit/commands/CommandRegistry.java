package com.minigit.commands;

import com.minigit.cli.Command;

import java.util.ArrayList;
import java.util.List;

/** The list of commands MiniGit ships with. */
public final class CommandRegistry {

    private CommandRegistry() {
    }

    public static List<Command> defaultCommands() {
        List<Command> working = List.of(
                new InitCommand(),
                new AddCommand(),
                new StatusCommand(),
                new CommitCommand(),
                new LogCommand(),
                new DiffCommand(),
                new BranchCommand(),
                new CheckoutCommand(),
                new ConfigCommand());
        List<Command> all = new ArrayList<>(working);
        all.add(new HelpCommand(working));
        return all;
    }
}
