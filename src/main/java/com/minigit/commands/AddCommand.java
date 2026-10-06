package com.minigit.commands;

import com.minigit.cli.Command;
import com.minigit.cli.CommandContext;
import com.minigit.core.Index;
import com.minigit.core.Repository;
import com.minigit.core.Stager;
import com.minigit.exceptions.MiniGitException;

import java.util.ArrayList;
import java.util.List;

/** {@code minigit add <pathspec>...} - stages files, directories, or everything with {@code .}. */
public class AddCommand implements Command {

    @Override
    public String name() {
        return "add";
    }

    @Override
    public String summary() {
        return "Stage file contents (and deletions) for the next commit";
    }

    @Override
    public String usage() {
        return "usage: minigit add <pathspec>...\n\n"
                + "Stores each file as a blob and records it in the index.\n"
                + "  minigit add README.md    stage one file\n"
                + "  minigit add src          stage a directory recursively\n"
                + "  minigit add .            stage everything, including deletions (.minigit is ignored)";
    }

    @Override
    public boolean mutatesRepository() {
        return true;
    }

    @Override
    public void execute(CommandContext context) {
        context.args().requireOnly();
        List<String> specs = context.args().positionals();
        if (specs.isEmpty()) {
            throw new MiniGitException("nothing specified, nothing added (usage: minigit add <file> or minigit add .)");
        }
        if (specs.stream().anyMatch(String::isBlank)) {
            throw new MiniGitException("empty pathspec (use '.' to add everything)");
        }
        Repository repo = context.repository();
        Index index = repo.loadIndex();
        Stager stager = new Stager(repo, index);

        List<Stager.Result> results = new ArrayList<>();
        for (String spec : specs) {
            results.addAll(stager.stage(repo.toRepoPath(context.workingDirectory(), spec)));
        }
        index.save();

        for (Stager.Result result : results) {
            String verb = result.action() == Stager.Action.REMOVED ? "Removed " : "Added ";
            context.console().println(verb + result.path());
        }
    }
}
