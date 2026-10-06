package com.minigit.core;

import com.minigit.exceptions.MiniGitException;
import com.minigit.hash.HashUtils;
import com.minigit.storage.FileStore;
import com.minigit.utils.TimeUtils;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Reads and writes HEAD and branch references.
 * {@code HEAD} contains {@code ref: refs/heads/<branch>}; each branch file holds a commit hash
 * (or is empty before the first commit). Branch names are matched exactly and compared
 * case-insensitively for collisions, so behaviour is identical on every file system.
 */
public class RefStore {

    private static final String HEAD_PREFIX = "ref: refs/heads/";
    private static final String NULL_HASH = "0".repeat(HashUtils.HASH_LENGTH);

    private final Path headFile;
    private final Path headsDir;
    private final Path headLog;

    public RefStore(Path headFile, Path headsDir, Path logsDir) {
        this.headFile = headFile;
        this.headsDir = headsDir;
        this.headLog = logsDir.resolve("HEAD");
    }

    /** Creates an empty reference for {@code branch} and points HEAD at it. */
    public void initialize(String branch) {
        FileStore.createDirectories(headsDir);
        FileStore.writeString(headsDir.resolve(branch), "");
        setHead(branch);
    }

    public String currentBranch() {
        String head = FileStore.readString(headFile).strip();
        if (!head.startsWith(HEAD_PREFIX) || !Branch.isValidName(head.substring(HEAD_PREFIX.length()))) {
            throw new MiniGitException("HEAD is corrupt: '" + head + "'");
        }
        return head.substring(HEAD_PREFIX.length());
    }

    public void setHead(String branch) {
        FileStore.writeString(headFile, HEAD_PREFIX + branch + "\n");
    }

    /** Exact-name match (so {@code Main} is not {@code main}, even on a case-insensitive file system). */
    public boolean branchExists(String name) {
        return listBranchNames().contains(name);
    }

    /** Commit the branch points to; empty for a branch without commits. */
    public Optional<String> resolveBranch(String name) {
        if (!branchExists(name)) {
            return Optional.empty();
        }
        String hash = FileStore.readString(headsDir.resolve(name)).strip();
        return hash.isEmpty() ? Optional.empty() : Optional.of(hash);
    }

    public Optional<String> headCommit() {
        return resolveBranch(currentBranch());
    }

    public void createBranch(String name, String commitHash) {
        Branch.validateName(name);
        for (String existing : listBranchNames()) {
            if (existing.equalsIgnoreCase(name)) {
                throw new MiniGitException("branch '" + name + "' already exists"
                        + (existing.equals(name) ? "" : " (as '" + existing + "'; branch names are case-insensitive)"));
            }
        }
        FileStore.writeString(headsDir.resolve(name), commitHash + "\n");
    }

    public void updateBranch(String name, String commitHash) {
        Branch.validateName(name);
        FileStore.writeString(headsDir.resolve(name), commitHash + "\n");
    }

    public List<Branch> listBranches() {
        String current = currentBranch();
        return listBranchNames().stream()
                .sorted(Comparator.naturalOrder())
                .map(name -> {
                    String hash = FileStore.readString(headsDir.resolve(name)).strip();
                    return new Branch(name, hash.isEmpty() ? null : hash, name.equals(current));
                })
                .toList();
    }

    /** Appends one line to {@code logs/HEAD} describing a movement of HEAD. */
    public void appendLog(String oldHash, String newHash, String message) {
        String line = String.join(" ", oldHash == null ? NULL_HASH : oldHash, newHash == null ? NULL_HASH : newHash,
                String.valueOf(TimeUtils.nowMillis()), message) + "\n";
        FileStore.appendString(headLog, line);
    }

    /** Names of all well-formed branch files; stray files (e.g. leftover temp files) are ignored. */
    private List<String> listBranchNames() {
        if (!FileStore.isDirectory(headsDir)) {
            return List.of();
        }
        return FileStore.listFiles(headsDir, dir -> true).stream()
                .map(file -> file.getFileName().toString())
                .filter(Branch::isValidName)
                .toList();
    }
}
