package com.minigit.commands;

import com.minigit.cli.Command;
import com.minigit.cli.CommandContext;
import com.minigit.cli.Console;
import com.minigit.core.Commit;
import com.minigit.core.Repository;
import com.minigit.exceptions.MiniGitException;
import com.minigit.utils.FileUtils;
import com.minigit.utils.TimeUtils;

import java.util.List;

/** {@code minigit log [--oneline]} - walks parent pointers from HEAD back to the first commit. */
public class LogCommand implements Command {

    private static final String ONELINE = "--oneline";
    private static final String MESSAGE_INDENT = "    ";

    @Override
    public String name() {
        return "log";
    }

    @Override
    public String summary() {
        return "Show commit history";
    }

    @Override
    public String usage() {
        return "usage: minigit log [--oneline]\n\n"
                + "Prints the commits reachable from HEAD, newest first.\n"
                + "  --oneline    one line per commit: <short hash> <subject>";
    }

    @Override
    public void execute(CommandContext context) {
        context.args().requireOnly(ONELINE);
        context.args().requireMaxPositionals(0);
        Repository repo = context.repository();
        Console console = context.console();
        String next = repo.refs().headCommit().orElseThrow(() -> new MiniGitException(
                "your current branch '" + repo.refs().currentBranch() + "' does not have any commits yet"));
        boolean oneline = context.args().hasFlag(ONELINE);

        boolean first = true;
        while (next != null) {
            Commit commit = repo.loadCommit(next);
            if (oneline) {
                console.println(commit.shortHash() + " " + commit.subject());
            } else {
                if (!first) {
                    console.println();
                }
                printFull(console, commit);
            }
            first = false;
            next = commit.parent();
        }
    }

    private void printFull(Console console, Commit commit) {
        console.println("commit " + commit.hash());
        console.println("Author: " + commit.author());
        console.println("Date:   " + TimeUtils.formatLocal(commit.timestamp()));
        console.println();
        List<String> lines = FileUtils.splitLines(commit.message());
        lines.forEach(line -> console.println(MESSAGE_INDENT + line));
    }
}
