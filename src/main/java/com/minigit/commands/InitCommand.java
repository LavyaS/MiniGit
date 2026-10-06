package com.minigit.commands;

import com.minigit.cli.Command;
import com.minigit.cli.CommandContext;
import com.minigit.core.Repository;

import java.nio.file.Path;
import java.util.List;

/** {@code minigit init [directory]} - creates an empty repository. */
public class InitCommand implements Command {

    @Override
    public String name() {
        return "init";
    }

    @Override
    public String summary() {
        return "Create an empty MiniGit repository";
    }

    @Override
    public String usage() {
        return "usage: minigit init [<directory>]\n\n"
                + "Creates a .minigit directory (HEAD, config, index, objects/, refs/heads/, logs/)\n"
                + "with an empty 'main' branch. Without <directory> the current directory is used.";
    }

    @Override
    public void execute(CommandContext context) {
        context.args().requireOnly();
        context.args().requireMaxPositionals(1);
        List<String> positionals = context.args().positionals();
        Path target = positionals.isEmpty()
                ? context.workingDirectory()
                : context.workingDirectory().resolve(positionals.get(0));
        Path metadata = target.toAbsolutePath().normalize().resolve(Repository.METADATA_DIR);
        if (Repository.exists(target)) {
            context.console().println("MiniGit repository already exists in " + metadata);
            return;
        }
        Repository.init(target);
        context.console().println("Initialized empty MiniGit repository in " + metadata);
    }
}
