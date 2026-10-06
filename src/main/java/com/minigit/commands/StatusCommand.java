package com.minigit.commands;

import com.minigit.cli.Command;
import com.minigit.cli.CommandContext;
import com.minigit.cli.Console;
import com.minigit.core.StatusReport;
import com.minigit.core.StatusReport.FileChange;

import java.util.List;

/** {@code minigit status} - shows staged, unstaged and untracked files. */
public class StatusCommand implements Command {

    @Override
    public String name() {
        return "status";
    }

    @Override
    public String summary() {
        return "Show the state of the working tree and the staging area";
    }

    @Override
    public String usage() {
        return "usage: minigit status\n\n"
                + "Lists changes to be committed (HEAD vs index), changes not staged for commit\n"
                + "(index vs working tree) and untracked files.";
    }

    @Override
    public void execute(CommandContext context) {
        context.args().requireOnly();
        context.args().requireMaxPositionals(0);
        StatusReport report = StatusReport.compute(context.repository());
        Console console = context.console();

        console.println("On branch " + report.branch());
        if (!report.hasCommits()) {
            console.println("No commits yet");
        }
        console.println();
        printChanges(console, "Changes to be committed:", report.staged());
        printChanges(console, "Changes not staged for commit:", report.unstaged());
        if (!report.untracked().isEmpty()) {
            console.println("Untracked files:");
            report.untracked().forEach(path -> console.println("  " + path));
            console.println();
        }
        if (report.isClean()) {
            console.println("nothing to commit, working tree clean");
        } else if (!report.hasStagedChanges() && report.unstaged().isEmpty()) {
            console.println("nothing added to commit but untracked files present (use \"minigit add\" to track)");
        }
    }

    private void printChanges(Console console, String heading, List<FileChange> changes) {
        if (changes.isEmpty()) {
            return;
        }
        console.println(heading);
        for (FileChange change : changes) {
            console.println("  " + change.type().label() + ": " + change.path());
        }
        console.println();
    }
}
