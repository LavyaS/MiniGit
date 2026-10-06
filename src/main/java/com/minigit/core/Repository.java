package com.minigit.core;

import com.minigit.exceptions.MiniGitException;
import com.minigit.exceptions.RepositoryNotFoundException;
import com.minigit.storage.FileStore;
import com.minigit.utils.FileUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * Entry point to everything stored in a {@code .minigit} directory. A {@code Repository} is a
 * cheap handle: it only knows paths and creates the collaborating objects on demand.
 */
public final class Repository {

    public static final String METADATA_DIR = RepoPath.METADATA_DIR;
    public static final String DEFAULT_BRANCH = "main";

    private static final String HEAD_FILE = "HEAD";
    private static final String CONFIG_FILE = "config";
    private static final String INDEX_FILE = "index";
    private static final String OBJECTS_DIR = "objects";
    private static final String REFS_DIR = "refs";
    private static final String HEADS_DIR = "heads";
    private static final String LOGS_DIR = "logs";

    private final Path root;
    private final Path metadataDir;
    private final ObjectStore objectStore;
    private final RefStore refs;
    private final WorkingTree workingTree;

    private Repository(Path root) {
        this.root = root;
        this.metadataDir = root.resolve(METADATA_DIR);
        this.objectStore = new ObjectStore(metadataDir.resolve(OBJECTS_DIR));
        this.refs = new RefStore(metadataDir.resolve(HEAD_FILE),
                metadataDir.resolve(REFS_DIR).resolve(HEADS_DIR), metadataDir.resolve(LOGS_DIR));
        this.workingTree = new WorkingTree(root);
    }

    /**
     * True if {@code directory} directly contains a complete MiniGit repository. HEAD is written last
     * by {@link #init}, so a directory left half-initialised by a crash is not mistaken for one.
     */
    public static boolean exists(Path directory) {
        return FileStore.isRegularFile(directory.toAbsolutePath().normalize().resolve(METADATA_DIR).resolve(HEAD_FILE));
    }

    /** Creates the {@code .minigit} layout in {@code directory} with an empty default branch. */
    public static Repository init(Path directory) {
        Path root = directory.toAbsolutePath().normalize();
        if (exists(root)) {
            throw new MiniGitException("a MiniGit repository already exists in " + root.resolve(METADATA_DIR));
        }
        Repository repo = new Repository(root);
        FileStore.createDirectories(repo.metadataDir.resolve(OBJECTS_DIR));
        FileStore.createDirectories(repo.metadataDir.resolve(REFS_DIR).resolve(HEADS_DIR));
        FileStore.createDirectories(repo.metadataDir.resolve(LOGS_DIR));
        repo.config().save();
        FileStore.writeString(repo.metadataDir.resolve(INDEX_FILE), "");
        repo.refs.initialize(DEFAULT_BRANCH);
        return repo;
    }

    /** Finds the repository containing {@code start} by walking up through parent directories. */
    public static Repository open(Path start) {
        Path current = start.toAbsolutePath().normalize();
        while (current != null) {
            if (exists(current)) {
                return new Repository(current);
            }
            current = current.getParent();
        }
        throw new RepositoryNotFoundException();
    }

    public Path root() {
        return root;
    }

    public Path metadataDir() {
        return metadataDir;
    }

    public ObjectStore objects() {
        return objectStore;
    }

    public RefStore refs() {
        return refs;
    }

    public WorkingTree workingTree() {
        return workingTree;
    }

    public Index loadIndex() {
        return Index.load(metadataDir.resolve(INDEX_FILE));
    }

    public Config config() {
        return Config.load(metadataDir.resolve(CONFIG_FILE));
    }

    public Commit loadCommit(String hash) {
        return Commit.parse(hash, new String(objectStore.readObject(hash), StandardCharsets.UTF_8));
    }

    public Commit saveCommit(Commit commit) {
        objectStore.writeObject(commit.hash(), commit.serialize().getBytes(StandardCharsets.UTF_8));
        return commit;
    }

    public Optional<Commit> headCommit() {
        return refs.headCommit().map(this::loadCommit);
    }

    /** Snapshot ({@code path -> blob hash}) of the commit HEAD points to; empty before the first commit. */
    public Map<String, String> headTree() {
        return headCommit().map(Commit::tree).orElse(Map.of());
    }

    /** Snapshot of the commit a branch points to; empty for a branch without commits. */
    public Map<String, String> branchTree(String branch) {
        return refs.resolveBranch(branch).map(this::loadCommit).map(Commit::tree).orElse(Map.of());
    }

    /**
     * Creates {@code name} pointing at the current commit and returns that commit's hash.
     *
     * @throws MiniGitException if the name is invalid or taken, or the current branch has no commits
     */
    public String createBranchAtHead(String name) {
        Branch.validateName(name);
        String head = refs.headCommit().orElseThrow(() -> new MiniGitException(
                "cannot create branch '" + name + "': the current branch has no commits yet"));
        refs.createBranch(name, head);
        return head;
    }

    /**
     * Converts a user supplied path (relative to {@code cwd}) to a repo-relative, slash-separated path,
     * spelled the way it really exists on disk (so {@code readme.md} becomes {@code README.md} on a
     * case-insensitive file system). The repository root itself becomes the empty string.
     */
    public String toRepoPath(Path cwd, String spec) {
        Path absolute;
        try {
            absolute = cwd.resolve(spec).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            throw new MiniGitException("invalid path '" + spec + "'", e);
        }
        if (!absolute.startsWith(root)) {
            throw new MiniGitException("'" + spec + "' is outside the repository");
        }
        return FileUtils.toRepoPath(root.relativize(onDiskSpelling(absolute)));
    }

    private Path onDiskSpelling(Path absolute) {
        Optional<Path> real = FileStore.realPath(absolute);
        Optional<Path> realRoot = FileStore.realPath(root);
        if (real.isPresent() && realRoot.isPresent() && real.get().startsWith(realRoot.get())) {
            return root.resolve(realRoot.get().relativize(real.get()));
        }
        return absolute;
    }
}
