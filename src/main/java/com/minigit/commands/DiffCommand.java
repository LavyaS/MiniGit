package com.minigit.commands;

import com.minigit.cli.Command;
import com.minigit.cli.CommandContext;
import com.minigit.core.Index;
import com.minigit.core.IndexEntry;
import com.minigit.core.Repository;
import com.minigit.core.WorkingTree;
import com.minigit.diff.DiffEngine;
import com.minigit.diff.DiffResult;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** {@code minigit diff [--cached]} - line diff of the working tree (or index) against the last commit. */
public class DiffCommand implements Command {

    private static final String CACHED = "--cached";
    private static final String NO_FILE = "/dev/null";
    private static final byte[] EMPTY = new byte[0];

    private final DiffEngine engine = new DiffEngine();

    @Override
    public String name() {
        return "diff";
    }

    @Override
    public String summary() {
        return "Show changes against the last commit";
    }

    @Override
    public String usage() {
        return "usage: minigit diff [--cached]\n\n"
                + "  (no option)  compare the working tree with the last commit\n"
                + "  --cached     compare the staging area with the last commit\n"
                + "Binary files are reported as 'Binary files differ'.";
    }

    @Override
    public void execute(CommandContext context) {
        context.args().requireOnly(CACHED);
        context.args().requireMaxPositionals(0);
        boolean cached = context.args().hasFlag(CACHED);
        Repository repo = context.repository();
        Map<String, String> head = repo.headTree();
        Index index = repo.loadIndex();

        Set<String> paths = new TreeSet<>(head.keySet());
        paths.addAll(index.paths());
        for (String path : paths) {
            String oldHash = head.get(path);
            String newHash = cached ? stagedHash(index, path) : workingHash(repo.workingTree(), index, path);
            if (Objects.equals(oldHash, newHash)) {
                continue;
            }
            byte[] oldContent = oldHash == null ? EMPTY : repo.objects().readObject(oldHash);
            byte[] newContent = newHash == null ? EMPTY
                    : cached ? repo.objects().readObject(newHash) : repo.workingTree().read(path);
            DiffResult result = engine.diff(oldContent, newContent);
            String text = result.format(oldHash == null ? NO_FILE : path, newHash == null ? NO_FILE : path);
            if (!text.isEmpty()) {
                // Remove only the final newline: trailing spaces on the last diff line are content.
                context.console().println(text.substring(0, text.length() - 1));
                context.console().println();
            }
        }
    }

    private String stagedHash(Index index, String path) {
        return index.get(path).map(IndexEntry::hash).orElse(null);
    }

    private String workingHash(WorkingTree workingTree, Index index, String path) {
        if (!workingTree.isFile(path)) {
            return null;
        }
        return workingTree.currentHash(path, index.get(path).orElse(null));
    }
}
