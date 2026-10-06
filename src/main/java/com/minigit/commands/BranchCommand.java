package com.minigit.commands;

import com.minigit.cli.Command;
import com.minigit.cli.CommandContext;
import com.minigit.core.Branch;
import com.minigit.core.Repository;
import com.minigit.hash.HashUtils;

import java.util.List;

/** {@code minigit branch [name]} - lists branches or creates a new one at HEAD. */
public class BranchCommand implements Command {

    @Override
    public String name() {
        return "branch";
    }

    @Override
    public String summary() {
        return "List branches or create a new one";
    }

    @Override
    public String usage() {
        return "usage: minigit branch [<name>]\n\n"
                + "  minigit branch           list branches; the current one is marked with '*'\n"
                + "  minigit branch <name>    create <name> pointing at the current commit\n"
                + "A branch is just a pointer to a commit; no files are copied.";
    }

    @Override
    public boolean mutatesRepository() {
        return true;
    }

    @Override
    public void execute(CommandContext context) {
        context.args().requireOnly();
        context.args().requireMaxPositionals(1);
        List<String> names = context.args().positionals();
        Repository repo = context.repository();
        if (names.isEmpty()) {
            list(context, repo);
        } else {
            create(context, repo, names.get(0));
        }
    }

    private void list(CommandContext context, Repository repo) {
        for (Branch branch : repo.refs().listBranches()) {
            context.console().println((branch.current() ? "* " : "  ") + branch.name());
        }
    }

    private void create(CommandContext context, Repository repo, String name) {
        String head = repo.createBranchAtHead(name);
        context.console().println("Created branch '" + name + "' at " + HashUtils.shortHash(head));
    }
}
