package com.minigit.core;

import com.minigit.exceptions.MiniGitException;
import com.minigit.hash.HashUtils;
import com.minigit.storage.FileStore;
import com.minigit.utils.FileUtils;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The project directory as the user sees it. Paths are always expressed relative to the
 * repository root with {@code /} separators. Any directory named {@code .minigit} (at any depth,
 * in any letter case) is never visited, and no path can resolve outside the root.
 */
public class WorkingTree {

    private final Path root;
    private final Set<Path> verifiedDirectories = new HashSet<>();
    private Path realRoot;

    public WorkingTree(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    /**
     * Absolute location of a repo-relative path.
     *
     * @throws MiniGitException if the path would leave the repository (for example via {@code ..})
     */
    public Path resolve(String repoPath) {
        Path resolved = root.resolve(repoPath).normalize();
        if (!resolved.startsWith(root) || (!repoPath.isEmpty() && resolved.equals(root))) {
            throw new MiniGitException("'" + repoPath + "' resolves to a location outside the repository");
        }
        return resolved;
    }

    /** Sorted repo-relative paths of every regular file, ignoring all {@code .minigit} directories. */
    public List<String> listFiles() {
        return FileStore.listFiles(root, dir -> RepoPath.isMetadataName(dir.getFileName().toString())).stream()
                .map(file -> FileUtils.toRepoPath(root.relativize(file)))
                .sorted()
                .toList();
    }

    /** True for a regular file; symbolic links are deliberately not followed. */
    public boolean isFile(String repoPath) {
        return FileStore.isRegularFile(resolve(repoPath));
    }

    public boolean isDirectory(String repoPath) {
        return FileStore.isDirectory(resolve(repoPath));
    }

    public byte[] read(String repoPath) {
        return FileStore.readBytes(resolve(repoPath));
    }

    /**
     * SHA-256 of the file as it is now. When {@code cached} still matches the file's size and
     * modification time, its hash is reused instead of reading the file again.
     */
    public String currentHash(String repoPath, IndexEntry cached) {
        Path file = resolve(repoPath);
        if (cached != null && cached.matchesFingerprint(FileStore.size(file), FileStore.lastModifiedMillis(file))) {
            return cached.hash();
        }
        return HashUtils.sha256(file);
    }

    /**
     * Fails if reading or writing {@code repoPath} would go through a symbolic link that leaves the
     * repository. The real root is resolved once and each directory is verified only once per command,
     * so staging thousands of files does not cost thousands of link resolutions.
     */
    public void requireInsideRepository(String repoPath) {
        Path parent = resolve(repoPath).getParent();
        if (parent == null || verifiedDirectories.contains(parent)) {
            return;
        }
        if (realRoot == null) {
            realRoot = FileStore.resolveLinks(root);
        }
        if (!FileStore.resolvesInside(parent, realRoot)) {
            throw new MiniGitException("'" + repoPath + "' is beyond a symbolic link that leaves the repository");
        }
        verifiedDirectories.add(parent);
    }

    /** Writes the blob {@code hash} to {@code repoPath}, replacing any existing file. */
    public void restore(String repoPath, ObjectStore objects, String hash) {
        requireInsideRepository(repoPath);
        Path target = resolve(repoPath);
        if (FileStore.isDirectory(target)) {
            throw new MiniGitException("cannot restore '" + repoPath + "': a directory is in the way");
        }
        objects.copyObjectTo(hash, target);
    }

    /** Deletes a file and any directories left empty by its removal. */
    public void delete(String repoPath) {
        requireInsideRepository(repoPath);
        Path target = resolve(repoPath);
        FileStore.delete(target);
        FileStore.deleteEmptyParents(target, root);
    }
}
