package com.minigit.commands;

import com.minigit.cli.Command;
import com.minigit.cli.CommandContext;
import com.minigit.core.Index;
import com.minigit.core.IndexEntry;
import com.minigit.core.Repository;
import com.minigit.core.WorkingTree;
import com.minigit.exceptions.MiniGitException;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code minigit checkout <branch>} / {@code checkout -b <name>}.
 *
 * <p>Switching rewrites only the files that differ between the two snapshots, and it is
 * all-or-nothing:
 * <ol>
 *   <li>Preflight: refuse if any file it would touch holds local work (staged changes, unstaged
 *       edits, an untracked file, or a file/directory in the way).</li>
 *   <li>Preflight: refuse if any blob it must restore is missing or damaged.</li>
 *   <li>Apply. If that fails half-way, the changes made so far are rolled back.</li>
 * </ol>
 */
public class CheckoutCommand implements Command {

    private static final String CREATE_FLAG = "-b";

    @Override
    public String name() {
        return "checkout";
    }

    @Override
    public String summary() {
        return "Switch branches (or create one with -b) and update the working tree";
    }

    @Override
    public String usage() {
        return "usage: minigit checkout <branch>\n"
                + "       minigit checkout -b <new-branch>\n\n"
                + "Restores the working tree and index to the branch's last commit and points HEAD at it.\n"
                + "Refuses to run if uncommitted changes would be overwritten.\n"
                + "  -b    create <new-branch> at the current commit and switch to it";
    }

    @Override
    public boolean mutatesRepository() {
        return true;
    }

    @Override
    public void execute(CommandContext context) {
        context.args().requireOnly(CREATE_FLAG);
        List<String> names = context.args().positionals();
        if (names.size() != 1) {
            throw new MiniGitException("checkout needs exactly one branch name (usage: minigit checkout <branch>)");
        }
        Repository repo = context.repository();
        String target = names.get(0);
        if (context.args().hasFlag(CREATE_FLAG)) {
            createAndSwitch(context, repo, target);
        } else {
            switchTo(context, repo, target);
        }
    }

    private void createAndSwitch(CommandContext context, Repository repo, String name) {
        String head = repo.createBranchAtHead(name);
        repo.refs().setHead(name);
        repo.refs().appendLog(head, head, "checkout: moving to " + name);
        context.console().println("Switched to a new branch '" + name + "'");
    }

    private void switchTo(CommandContext context, Repository repo, String branch) {
        if (!repo.refs().branchExists(branch)) {
            throw new MiniGitException("branch '" + branch + "' does not exist");
        }
        if (branch.equals(repo.refs().currentBranch())) {
            context.console().println("Already on '" + branch + "'");
            return;
        }
        Map<String, String> from = repo.headTree();
        Map<String, String> to = repo.branchTree(branch);
        Index index = repo.loadIndex();

        List<String> conflicts = findConflicts(repo.workingTree(), index, from, to);
        if (!conflicts.isEmpty()) {
            throw new MiniGitException("uncommitted changes would be overwritten by checkout:\n"
                    + conflicts.stream().map(path -> "\t" + path).reduce((a, b) -> a + "\n" + b).orElse("")
                    + "\nCommit or discard your changes before switching branches.");
        }
        verifyObjectsToRestore(repo, from, to);

        String oldHead = repo.refs().headCommit().orElse(null);
        try {
            applySnapshot(repo, index, from, to);
        } catch (RuntimeException failure) {
            rollback(repo, index, from, to);
            throw new MiniGitException("checkout failed and was rolled back: " + failure.getMessage(), failure);
        }
        index.save();
        repo.refs().setHead(branch);
        repo.refs().appendLog(oldHead, repo.refs().headCommit().orElse(null), "checkout: moving to " + branch);
        context.console().println("Switched to branch '" + branch + "'");
    }

    /** Paths the checkout will touch that hold local work, or that cannot be written at all. */
    private List<String> findConflicts(WorkingTree workingTree, Index index,
                                       Map<String, String> from, Map<String, String> to) {
        Set<String> touched = touchedPaths(from, to);
        Set<String> deletions = new TreeSet<>();
        touched.stream().filter(path -> !to.containsKey(path)).forEach(deletions::add);

        Set<String> conflicts = new TreeSet<>();
        for (String path : touched) {
            if (hasLocalWork(workingTree, index, path, from.get(path), to.get(path))) {
                conflicts.add(path);
            }
            if (to.containsKey(path)) {
                conflicts.addAll(blockers(workingTree, deletions, path));
            }
        }
        return List.copyOf(conflicts);
    }

    private boolean hasLocalWork(WorkingTree workingTree, Index index, String path, String fromHash, String toHash) {
        IndexEntry entry = index.get(path).orElse(null);
        String stagedHash = entry == null ? null : entry.hash();
        String workingHash = workingTree.isFile(path) ? workingTree.currentHash(path, entry) : null;

        if (Objects.equals(workingHash, toHash) && Objects.equals(stagedHash, fromHash)) {
            return false;
        }
        boolean untouchedByUser = Objects.equals(stagedHash, fromHash) && Objects.equals(workingHash, fromHash);
        boolean alreadyDeleted = toHash == null && workingHash == null && Objects.equals(stagedHash, fromHash);
        return !(untouchedByUser || alreadyDeleted);
    }

    /**
     * Things that would make writing {@code path} fail half-way: a file where a parent directory must
     * be, or a directory still holding other files where the file must be.
     */
    private Set<String> blockers(WorkingTree workingTree, Set<String> deletions, String path) {
        Set<String> blocked = new TreeSet<>();
        for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1)) {
            String parent = path.substring(0, slash);
            if (workingTree.isFile(parent) && !deletions.contains(parent)) {
                blocked.add(parent);
            }
        }
        if (workingTree.isDirectory(path)) {
            String prefix = path + "/";
            boolean keepsFiles = workingTree.listFiles().stream()
                    .anyMatch(file -> file.startsWith(prefix) && !deletions.contains(file));
            if (keepsFiles) {
                blocked.add(path);
            }
        }
        return blocked;
    }

    /** Every blob that is about to be written must exist and still match its hash. */
    private void verifyObjectsToRestore(Repository repo, Map<String, String> from, Map<String, String> to) {
        for (String path : touchedPaths(from, to)) {
            String hash = to.get(path);
            if (hash != null) {
                try {
                    repo.objects().verifyObject(hash);
                } catch (MiniGitException e) {
                    throw new MiniGitException("cannot restore '" + path + "': " + e.getMessage());
                }
            }
        }
    }

    private void applySnapshot(Repository repo, Index index, Map<String, String> from, Map<String, String> to) {
        WorkingTree workingTree = repo.workingTree();
        Set<String> touched = touchedPaths(from, to);
        for (String path : touched) {
            if (!to.containsKey(path)) {
                workingTree.delete(path);
                index.remove(path);
            }
        }
        for (String path : touched) {
            String hash = to.get(path);
            if (hash != null) {
                workingTree.restore(path, repo.objects(), hash);
                index.put(path, IndexEntry.forFile(hash, workingTree.resolve(path)));
            }
        }
    }

    /** Best effort: put every touched path back to its previous state (those paths had no local work). */
    private void rollback(Repository repo, Index index, Map<String, String> from, Map<String, String> to) {
        try {
            applySnapshot(repo, index, to, from);
            index.save();
        } catch (RuntimeException ignored) {
            // The original failure is the one worth reporting.
        }
    }

    private static Set<String> touchedPaths(Map<String, String> from, Map<String, String> to) {
        Set<String> touched = new TreeSet<>();
        from.forEach((path, hash) -> {
            if (!Objects.equals(hash, to.get(path))) {
                touched.add(path);
            }
        });
        to.forEach((path, hash) -> {
            if (!Objects.equals(hash, from.get(path))) {
                touched.add(path);
            }
        });
        return touched;
    }
}
