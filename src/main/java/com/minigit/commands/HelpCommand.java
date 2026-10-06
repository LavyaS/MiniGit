package com.minigit.commands;

import com.minigit.cli.Command;
import com.minigit.cli.CommandContext;
import com.minigit.cli.CommandHandler;
import com.minigit.cli.Console;
import com.minigit.exceptions.MiniGitException;

import java.util.List;

/** {@code minigit help [command]} - overview of all commands, or detailed usage of one. */
public class HelpCommand implements Command {

    private final List<Command> commands;

    /** @param commands the other commands to describe (this command is listed automatically) */
    public HelpCommand(List<Command> commands) {
        this.commands = commands;
    }

    @Override
    public String name() {
        return "help";
    }

    @Override
    public String summary() {
        return "Show help for MiniGit or one command";
    }

    @Override
    public String usage() {
        return "usage: minigit help [<command>]";
    }

    @Override
    public void execute(CommandContext context) {
        List<String> topics = context.args().positionals();
        Console console = context.console();
        if (topics.isEmpty()) {
            printOverview(console);
        } else {
            console.println(find(topics.get(0)).usage());
        }
    }

    private Command find(String name) {
        if (name.equals(name())) {
            return this;
        }
        return commands.stream().filter(c -> c.name().equals(name)).findFirst()
                .orElseThrow(() -> new MiniGitException("no help available for '" + name + "'"));
    }

    private void printOverview(Console console) {
        console.println("MiniGit " + CommandHandler.VERSION + " - a small educational version control system");
        console.println();
        console.println("usage: minigit <command> [<args>]");
        console.println();
        console.println("Commands:");
        for (Command command : commands) {
            console.println(String.format("  %-10s %s", command.name(), command.summary()));
        }
        console.println(String.format("  %-10s %s", name(), summary()));
        console.println();
        console.println("Run 'minigit help <command>' for details, or 'minigit --version'.");
    }
}
