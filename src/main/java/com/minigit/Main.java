package com.minigit;

import com.minigit.cli.CommandHandler;
import com.minigit.cli.Console;
import com.minigit.commands.CommandRegistry;

import java.nio.file.Path;

/** Process entry point: wires the console, the current directory and the commands together. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        Console console = Console.system();
        Path workingDirectory = Path.of("").toAbsolutePath();
        CommandHandler handler = new CommandHandler(console, workingDirectory, CommandRegistry.defaultCommands());
        System.exit(handler.run(args));
    }
}
