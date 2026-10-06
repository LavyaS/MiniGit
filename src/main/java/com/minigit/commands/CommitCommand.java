package com.minigit.commands;

import com.minigit.cli.Command;
import com.minigit.cli.CommandContext;
import com.minigit.core.Commit;
import com.minigit.core.Repository;
import com.minigit.core.StatusReport;
import com.minigit.core.StatusReport.FileChange;
import com.minigit.exceptions.MiniGitException;
import com.minigit.utils.TimeUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@code minigit commit -m "message"} - records the staged snapshot as a new commit. */
public class CommitCommand implements Command {

    @Override
    public String name() {
        return "commit";
    }

    @Override
    public String summary() {
        return "Record the staged snapshot as a new commit";
    }

    @Override
    public String usage() {
        return "usage: minigit commit -m <message>\n\n"
                + "Creates a commit from the index, with the current commit as its parent, and moves\n"
                + "the current branch to it. Requires user.name and user.email (see 'minigit help config').\n"
                + "Fails with 'nothing to commit' if the index equals the last commit.";
    }

    @Override
    public Set<String> valueOptions() {
        return Set.of("-m", "--message");
    }

    @Override
    public boolean mutatesRepository() {
        return true;
    }

    @Override
    public void execute(CommandContext context) {
        context.args().requireOnly("-m", "--message");
        context.args().requireMaxPositionals(0);
        String message = context.args().option("-m", "--message")
                .orElseThrow(() -> new MiniGitException("no commit message given (usage: minigit commit -m \"message\")"))
                .strip();
        if (message.isEmpty()) {
            throw new MiniGitException("aborting commit due to empty commit message");
        }

        Repository repo = context.repository();
        Map<String, String> snapshot = repo.loadIndex().tree();
        List<FileChange> changes = StatusReport.compareTrees(repo.headTree(), snapshot);
        if (changes.isEmpty()) {
            throw new MiniGitException("nothing to commit");
        }
        requireBlobsPresent(repo, snapshot);
        String author = repo.config().requireAuthor();

        String branch = repo.refs().currentBranch();
        String parent = repo.refs().headCommit().orElse(null);
        Commit commit = repo.saveCommit(Commit.create(parent, author, TimeUtils.nowMillis(), message, snapshot));
        repo.refs().updateBranch(branch, commit.hash());
        repo.refs().appendLog(parent, commit.hash(), "commit: " + commit.subject());

        context.console().println("[" + branch + " " + commit.shortHash() + "] " + commit.subject());
        context.console().println(" " + changes.size() + (changes.size() == 1 ? " file" : " files") + " changed");
    }

    /** Never record a commit that points at file contents the object store does not have. */
    private void requireBlobsPresent(Repository repo, Map<String, String> snapshot) {
        snapshot.forEach((path, blob) -> {
            if (!repo.objects().objectExists(blob)) {
                throw new MiniGitException("the stored content of '" + path + "' is missing from the object store; "
                        + "run 'minigit add " + path + "' again");
            }
        });
    }
}
