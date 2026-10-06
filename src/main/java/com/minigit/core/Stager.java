package com.minigit.core;

import com.minigit.exceptions.MiniGitException;
import com.minigit.storage.FileStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Moves working-tree state into the index: stores blobs and records (or removes) index entries.
 * The caller is responsible for saving the {@link Index} afterwards.
 */
public class Stager {

    /** What happened to one path. */
    public enum Action { ADDED, UNCHANGED, REMOVED }

    public record Result(String path, Action action) {
    }

    private final Repository repo;
    private final Index index;

    public Stager(Repository repo, Index index) {
        this.repo = repo;
        this.index = index;
    }

    /**
     * Stages a file, a directory (recursively) or the whole tree (empty path). Files that no longer
     * exist but are still in the index are staged as deletions.
     */
    public List<Result> stage(String repoPath) {
        WorkingTree workingTree = repo.workingTree();
        if (RepoPath.touchesMetadata(repoPath)) {
            throw new MiniGitException("pathspec '" + repoPath + "' is MiniGit's own metadata and cannot be added");
        }
        if (workingTree.isFile(repoPath)) {
            return List.of(stageFile(repoPath));
        }
        String prefix = repoPath.isEmpty() ? "" : repoPath + "/";
        boolean isDirectory = repoPath.isEmpty() || workingTree.isDirectory(repoPath);
        List<String> deletions = index.paths().stream()
                .filter(path -> path.startsWith(prefix) && !workingTree.isFile(path)).toList();
        if (!isDirectory && deletions.isEmpty() && !index.contains(repoPath)) {
            throw new MiniGitException("pathspec '" + repoPath + "' did not match any files");
        }

        List<Result> results = new ArrayList<>();
        if (isDirectory) {
            for (String path : workingTree.listFiles()) {
                if (path.startsWith(prefix)) {
                    Result result = stageFile(path);
                    if (result.action() != Action.UNCHANGED) {
                        results.add(result);
                    }
                }
            }
        }
        if (index.contains(repoPath) && !workingTree.isFile(repoPath)) {
            index.remove(repoPath);
            results.add(new Result(repoPath, Action.REMOVED));
        }
        for (String path : deletions) {
            if (index.contains(path)) {
                index.remove(path);
                results.add(new Result(path, Action.REMOVED));
            }
        }
        return results;
    }

    private Result stageFile(String path) {
        if (!RepoPath.isSafe(path)) {
            throw new MiniGitException("unsupported file name (backslashes and control characters are not "
                    + "allowed): " + path);
        }
        if (path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0) {
            throw new MiniGitException("file names containing line breaks are not supported: " + path);
        }
        repo.workingTree().requireInsideRepository(path);
        Path file = repo.workingTree().resolve(path);
        IndexEntry previous = index.get(path).orElse(null);
        long size = FileStore.size(file);
        long mtime = FileStore.lastModifiedMillis(file);
        if (previous != null && previous.matchesFingerprint(size, mtime)) {
            return new Result(path, Action.UNCHANGED);
        }
        // The fingerprint is taken before the content is read: if the file changes meanwhile, the
        // stale fingerprint will not match and the next command re-hashes it.
        String hash = repo.objects().storeFile(file);
        removeConflictingEntries(path);
        index.put(path, new IndexEntry(hash, size, mtime));
        boolean same = previous != null && previous.hash().equals(hash);
        return new Result(path, same ? Action.UNCHANGED : Action.ADDED);
    }

    /** A path cannot be a file and a directory at once: drop entries for its parents and its children. */
    private void removeConflictingEntries(String path) {
        for (int slash = path.indexOf('/'); slash >= 0; slash = path.indexOf('/', slash + 1)) {
            index.remove(path.substring(0, slash));
        }
        index.removeUnder(path);
    }
}
