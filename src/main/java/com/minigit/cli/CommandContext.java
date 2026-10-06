package com.minigit.cli;

import com.minigit.core.Repository;

import java.nio.file.Path;

/** Everything a command needs: where it runs, its arguments, and where to print. */
public final class CommandContext {

    private final Path workingDirectory;
    private final ParsedArgs args;
    private final Console console;
    private Repository repository;

    public CommandContext(Path workingDirectory, ParsedArgs args, Console console) {
        this.workingDirectory = workingDirectory;
        this.args = args;
        this.console = console;
    }

    public Path workingDirectory() {
        return workingDirectory;
    }

    public ParsedArgs args() {
        return args;
    }

    public Console console() {
        return console;
    }

    /** The repository containing the working directory; fails if there is none. Opened lazily. */
    public Repository repository() {
        if (repository == null) {
            repository = Repository.open(workingDirectory);
        }
        return repository;
    }
}
