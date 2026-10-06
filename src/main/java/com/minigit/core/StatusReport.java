package com.minigit.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Three-way comparison between HEAD, the index and the working tree.
 *
 * @param branch    current branch name
 * @param hasCommits whether the branch has at least one commit
 * @param staged    differences between HEAD and the index ("changes to be committed")
 * @param unstaged  differences between the index and the working tree
 * @param untracked files present in the working tree but unknown to the index
 */
public record StatusReport(String branch, boolean hasCommits, List<FileChange> staged,
                           List<FileChange> unstaged, List<String> untracked) {

    /** How a file differs between two snapshots. */
    public enum ChangeType {
        NEW("new file"),
        MODIFIED("modified"),
        DELETED("deleted");

        private final String label;

        ChangeType(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record FileChange(String path, ChangeType type) {
    }

    public boolean hasStagedChanges() {
        return !staged.isEmpty();
    }

    public boolean isClean() {
        return staged.isEmpty() && unstaged.isEmpty() && untracked.isEmpty();
    }

    public static StatusReport compute(Repository repo) {
        Index index = repo.loadIndex();
        WorkingTree workingTree = repo.workingTree();
        List<FileChange> staged = compareTrees(repo.headTree(), index.tree());

        List<FileChange> unstaged = new ArrayList<>();
        index.entries().forEach((path, entry) -> {
            if (!workingTree.isFile(path)) {
                unstaged.add(new FileChange(path, ChangeType.DELETED));
            } else if (!workingTree.currentHash(path, entry).equals(entry.hash())) {
                unstaged.add(new FileChange(path, ChangeType.MODIFIED));
            }
        });

        List<String> untracked = workingTree.listFiles().stream().filter(path -> !index.contains(path)).toList();
        return new StatusReport(repo.refs().currentBranch(), repo.refs().headCommit().isPresent(),
                staged, unstaged, untracked);
    }

    /** Describes how to get from snapshot {@code base} to snapshot {@code target}, sorted by path. */
    public static List<FileChange> compareTrees(Map<String, String> base, Map<String, String> target) {
        Set<String> paths = new TreeSet<>(base.keySet());
        paths.addAll(target.keySet());
        List<FileChange> changes = new ArrayList<>();
        for (String path : paths) {
            String before = base.get(path);
            String after = target.get(path);
            if (before == null) {
                changes.add(new FileChange(path, ChangeType.NEW));
            } else if (after == null) {
                changes.add(new FileChange(path, ChangeType.DELETED));
            } else if (!before.equals(after)) {
                changes.add(new FileChange(path, ChangeType.MODIFIED));
            }
        }
        return changes;
    }
}
