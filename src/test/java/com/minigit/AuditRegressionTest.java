package com.minigit;

import com.minigit.core.Commit;
import com.minigit.core.Index;
import com.minigit.core.RepoPath;
import com.minigit.exceptions.MiniGitException;
import com.minigit.hash.HashUtils;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** One test per defect found in the engineering audit; each failed before the corresponding fix. */
class AuditRegressionTest {

    @TempDir
    Path dir;

    private Cli cli;

    private void init() {
        cli = new Cli(dir).initialized();
    }

    private Set<String> staged() {
        return cli.repo().loadIndex().paths();
    }

    /** Writes a hand-made commit object (with an honest hash) into a repository and points a branch at it. */
    private static void plantCommit(Path repoDir, String branch, String treeLine) throws IOException {
        String text = "author X <x@x>\ntimestamp 1\ntree 1\n" + treeLine + "\n\nevil";
        String hash = HashUtils.sha256(text);
        Path object = repoDir.resolve(".minigit/objects").resolve(hash.substring(0, 2)).resolve(hash.substring(2));
        Files.createDirectories(object.getParent());
        Files.writeString(object, text);
        Files.writeString(repoDir.resolve(".minigit/refs/heads").resolve(branch), hash + "\n");
    }

    // ---------------- CRITICAL: path traversal ----------------

    @Test
    void craftedCommitWithParentTraversalCannotWriteOutsideTheRepository() throws IOException {
        Path repoDir = Files.createDirectory(dir.resolve("repo"));
        cli = new Cli(repoDir).initialized();
        cli.write("a.txt", "a\n");
        cli.commitAll("base");
        plantCommit(repoDir, "evil", cli.repo().headTree().get("a.txt") + " ../pwned.txt");

        String err = cli.fails("checkout", "evil");

        assertTrue(err.startsWith("fatal: "), err);
        assertFalse(Files.exists(dir.resolve("pwned.txt")), "a file was written outside the repository");
        assertEquals("main", cli.repo().refs().currentBranch());
    }

    @Test
    void commitParserRejectsUnsafePaths() {
        String blob = "a".repeat(64);
        for (String bad : List.of("../x", "a/../../x", "/etc/passwd", "a//b", "a\\b", ".minigit/HEAD", "A/.MINIGIT/x", "./x")) {
            String text = "author X <x@x>\ntimestamp 1\ntree 1\n" + blob + " " + bad + "\n\nm";
            assertThrows(MiniGitException.class, () -> Commit.parse("h", text), bad);
        }
    }

    @Test
    void commitParserRejectsTruncatedTreeAndBadBlobHash() {
        String blob = "a".repeat(64);
        assertThrows(MiniGitException.class,
                () -> Commit.parse("h", "author X <x@x>\ntimestamp 1\ntree 3\n" + blob + " a\n\nm"));
        assertThrows(MiniGitException.class,
                () -> Commit.parse("h", "author X <x@x>\ntimestamp 1\ntree 1\n" + "Z".repeat(64) + " a\n\nm"));
    }

    @Test
    void indexWithUnsafePathIsReportedAsCorrupt() throws IOException {
        init();
        Files.writeString(dir.resolve(".minigit/index"), "a".repeat(64) + " 1 1 ../escape.txt\n");
        assertTrue(cli.fails("status").contains("index file is corrupt"));
    }

    @Test
    void repoPathRulesAreStrict() {
        assertTrue(RepoPath.isSafe("src/Main.java"));
        assertTrue(RepoPath.isSafe("dir with space/ünï/файл.txt"));
        assertFalse(RepoPath.isSafe(""));
        assertFalse(RepoPath.isSafe("a/../b"));
        assertFalse(RepoPath.isSafe(".minigit"));
        assertFalse(RepoPath.isSafe("x/.MiniGit/y"));
    }

    @Test
    void symlinkedDirectoryPointingOutsideIsNeverFollowedWhenStaging() throws IOException {
        init();
        Path outside = Files.createDirectory(dir.resolveSibling(dir.getFileName() + "-secret"));
        Files.writeString(outside.resolve("secret.txt"), "top secret\n");
        try {
            Files.createSymbolicLink(dir.resolve("link"), outside);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("symbolic links are not available here: " + e);
        }

        cli.ok("add", ".");
        assertTrue(staged().isEmpty(), staged().toString());
        assertTrue(cli.run("add", "link/secret.txt").exit() != 0);
        assertTrue(staged().isEmpty());
    }

    @Test
    void windowsJunctionPointingOutsideIsSkippedByAddDotAndRejectedWhenNamed() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"), "Windows only");
        init();
        Path outside = Files.createDirectory(dir.resolveSibling(dir.getFileName() + "-junction-secret"));
        Files.writeString(outside.resolve("secret.txt"), "top secret\n");
        cli.write("a.txt", "a");
        Process mklink = new ProcessBuilder("cmd", "/c", "mklink", "/J", dir.resolve("link").toString(), outside.toString())
                .redirectErrorStream(true).start();
        Assumptions.assumeTrue(mklink.waitFor() == 0, "cannot create a junction here");

        cli.ok("add", ".");

        assertEquals(Set.of("a.txt"), staged());
        assertTrue(cli.fails("add", "link/secret.txt").contains("beyond a symbolic link"));
    }

    // ---------------- HIGH: metadata directory is never tracked ----------------

    @Test
    void nestedMetadataDirectoriesAreNotStaged() {
        init();
        cli.write("a.txt", "a");
        cli.write("sub/.minigit/HEAD", "ref: refs/heads/main\n");
        cli.write("sub/f.txt", "f");

        cli.ok("add", ".");

        assertEquals(Set.of("a.txt", "sub/f.txt"), staged());
    }

    @Test
    void metadataDirectoryCannotBeAddedByChangingItsLetterCase() {
        init();
        cli.write("a.txt", "a");
        assertTrue(cli.fails("add", ".MINIGIT/HEAD").contains("metadata"));
        assertTrue(cli.fails("add", ".MiniGit").contains("metadata"));
        assertTrue(staged().isEmpty());
    }

    // ---------------- HIGH: index consistency ----------------

    @Test
    void replacingAFileWithADirectoryNeverLeavesBothInTheIndex() {
        init();
        cli.write("a", "file\n");
        cli.commitAll("file");
        cli.delete("a");
        cli.write("a/b.txt", "inside\n");

        cli.ok("add", "a/b.txt");

        assertEquals(Set.of("a/b.txt"), staged());
        cli.ok("commit", "-m", "now a directory");
        assertEquals(Set.of("a/b.txt"), cli.repo().headTree().keySet());
    }

    @Test
    void replacingADirectoryWithAFileDropsTheOldChildren() {
        init();
        cli.write("d/x.txt", "x\n");
        cli.write("d/y.txt", "y\n");
        cli.commitAll("dir");
        cli.delete("d/x.txt");
        cli.delete("d/y.txt");
        cli.delete("d");
        cli.write("d", "now a file\n");

        cli.ok("add", ".");

        assertEquals(Set.of("d"), staged());
    }

    @Test
    void checkoutHandlesFileDirectoryTransitionsBetweenBranches() {
        init();
        cli.write("thing", "file\n");
        cli.commitAll("file");
        cli.ok("checkout", "-b", "dirs");
        cli.delete("thing");
        cli.write("thing/inner.txt", "dir\n");
        cli.commitAll("dir");

        cli.ok("checkout", "main");
        assertEquals("file\n", cli.read("thing"));
        cli.ok("checkout", "dirs");
        assertEquals("dir\n", cli.read("thing/inner.txt"));
        assertTrue(cli.ok("status").contains("working tree clean"));
    }

    // ---------------- HIGH: branches on case-insensitive file systems ----------------

    @Test
    void branchLookupIsExactAndCaseCollisionsAreRejected() {
        init();
        cli.write("a.txt", "a");
        cli.commitAll("c");

        assertTrue(cli.fails("branch", "Main").contains("already exists"));
        assertEquals("fatal: branch 'Main' does not exist", cli.fails("checkout", "Main").trim());
        assertEquals("main", cli.repo().refs().currentBranch());
        assertTrue(cli.fails("checkout", "-b", "MAIN").contains("already exists"));
    }

    @Test
    void windowsReservedAndOtherUnsafeBranchNamesAreRejected() {
        init();
        cli.write("a.txt", "a");
        cli.commitAll("c");
        for (String bad : List.of("con", "NUL", "aux.txt", "com1", "lpt9", "x.lock", "ends.", "a..b", ".hidden", "sl/ash", "back\\slash")) {
            assertTrue(cli.fails("branch", bad).contains("not a valid branch name"), bad);
        }
        assertEquals(List.of("main"), cli.repo().refs().listBranches().stream().map(b -> b.name()).toList());
    }

    @Test
    void corruptHeadPointingOutsideRefsIsRejected() throws IOException {
        init();
        cli.write("a.txt", "a");
        cli.ok("add", ".");
        Files.writeString(dir.resolve(".minigit/HEAD"), "ref: refs/heads/../../escaped\n");

        assertTrue(cli.fails("commit", "-m", "x").contains("HEAD is corrupt"));
        assertFalse(Files.exists(dir.resolve(".minigit/escaped")));
    }

    // ---------------- HIGH: damaged repositories ----------------

    @Test
    void checkoutRefusesToRestoreACorruptBlobAndChangesNothing() throws IOException {
        init();
        cli.write("a.txt", "main version\n");
        cli.write("keep.txt", "keep\n");
        cli.commitAll("base");
        cli.ok("checkout", "-b", "feat");
        cli.write("a.txt", "feat version\n");
        cli.write("new.txt", "new\n");
        cli.commitAll("feat");
        String newBlob = cli.repo().headTree().get("new.txt");
        cli.ok("checkout", "main");
        Files.writeString(cli.repo().objects().pathFor(newBlob), "garbage");

        String err = cli.fails("checkout", "feat");

        assertTrue(err.contains("new.txt") && err.contains("corrupt"), err);
        assertEquals("main", cli.repo().refs().currentBranch());
        assertEquals("main version\n", cli.read("a.txt"));
        assertFalse(cli.exists("new.txt"));
    }

    @Test
    void checkoutRefusesWhenABlobIsMissing() throws IOException {
        init();
        cli.write("a.txt", "one\n");
        cli.commitAll("base");
        cli.ok("checkout", "-b", "feat");
        cli.write("a.txt", "two\n");
        cli.commitAll("feat");
        String blob = cli.repo().headTree().get("a.txt");
        cli.ok("checkout", "main");
        Files.delete(cli.repo().objects().pathFor(blob));

        assertTrue(cli.fails("checkout", "feat").contains("a.txt"));
        assertEquals("one\n", cli.read("a.txt"));
    }

    @Test
    void commitRefusesToReferenceAMissingBlob() throws IOException {
        init();
        cli.write("a.txt", "a\n");
        cli.ok("add", "a.txt");
        Files.delete(cli.repo().objects().pathFor(cli.repo().loadIndex().get("a.txt").orElseThrow().hash()));

        assertTrue(cli.fails("commit", "-m", "x").contains("missing from the object store"));
        assertTrue(cli.repo().refs().headCommit().isEmpty());
    }

    @Test
    void readingATamperedObjectIsDetected() throws IOException {
        init();
        cli.write("a.txt", "a\n");
        cli.commitAll("c");
        String commit = cli.repo().refs().headCommit().orElseThrow();
        Files.writeString(cli.repo().objects().pathFor(commit), "tampered");

        assertTrue(cli.fails("log").contains("corrupt"));
    }

    @Test
    void rewritingACommitToPointAtItselfIsCaughtBecauseHistoryIsHashChecked() throws IOException {
        init();
        cli.write("a.txt", "a\n");
        cli.commitAll("c");
        String head = cli.repo().refs().headCommit().orElseThrow();
        Path object = cli.repo().objects().pathFor(head);
        Files.writeString(object, "parent " + head + "\n" + Files.readString(object));

        // A real cycle is impossible (a commit's hash covers its parent's hash); tampering must fail loudly, not hang.
        String err = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> cli.run("log", "--oneline").err());
        assertTrue(err.contains("corrupt"), err);
    }

    @Test
    void halfInitialisedDirectoryIsNotTreatedAsARepositoryAndCanBeReinitialised() throws IOException {
        cli = new Cli(dir);
        Files.createDirectories(dir.resolve(".minigit/objects"));

        assertTrue(cli.fails("status").contains("not a MiniGit repository"));
        assertTrue(cli.ok("init").startsWith("Initialized empty MiniGit repository"));
        cli.ok("status");
    }

    // ---------------- HIGH: checkout is all-or-nothing ----------------

    @Test
    void untrackedDirectoryInTheWayBlocksCheckoutBeforeAnythingIsDeleted() throws IOException {
        init();
        cli.write("a.txt", "a\n");
        cli.write("z.txt", "z\n");
        cli.commitAll("base");
        cli.ok("checkout", "-b", "feat");
        cli.delete("a.txt");
        cli.write("d", "d is a file on feat\n");
        cli.commitAll("feat");
        cli.ok("checkout", "main");
        cli.write("d/keep.txt", "mine\n");

        String err = cli.fails("checkout", "feat");

        assertTrue(err.contains("would be overwritten") && err.contains("d"), err);
        assertEquals("a\n", cli.read("a.txt"), "tracked file must not have been deleted by a refused checkout");
        assertEquals("mine\n", cli.read("d/keep.txt"));
        assertEquals("main", cli.repo().refs().currentBranch());
    }

    @Test
    void untrackedFileInPlaceOfAParentDirectoryBlocksCheckout() {
        init();
        cli.write("a.txt", "a\n");
        cli.commitAll("base");
        cli.ok("checkout", "-b", "feat");
        cli.write("lib/util.txt", "util\n");
        cli.commitAll("feat");
        cli.ok("checkout", "main");
        cli.write("lib", "i am a file\n");

        assertTrue(cli.fails("checkout", "feat").contains("lib"));
        assertEquals("i am a file\n", cli.read("lib"));
    }

    @Test
    void readOnlyFilesDoNotBreakCheckout() throws IOException {
        init();
        cli.write("ro.txt", "v1\n");
        cli.commitAll("base");
        cli.ok("checkout", "-b", "feat");
        cli.write("ro.txt", "v2\n");
        cli.write("gone.txt", "g\n");
        cli.commitAll("feat");
        cli.ok("checkout", "main");
        Path readOnly = dir.resolve("ro.txt");
        assertTrue(readOnly.toFile().setReadOnly());

        cli.ok("checkout", "feat");
        assertEquals("v2\n", cli.read("ro.txt"));

        assertTrue(dir.resolve("gone.txt").toFile().setReadOnly());
        cli.ok("checkout", "main");
        assertFalse(cli.exists("gone.txt"));
        assertEquals("v1\n", cli.read("ro.txt"));
    }

    // ---------------- MEDIUM: output accuracy ----------------

    @Test
    void diffAgreesWithStatusWhenOnlyTheTrailingNewlineChanged() throws IOException {
        init();
        Files.writeString(dir.resolve("f.txt"), "one\ntwo\n");
        cli.commitAll("base");
        Files.writeString(dir.resolve("f.txt"), "one\ntwo");

        assertTrue(cli.ok("status").contains("modified: f.txt"));
        String diff = cli.ok("diff");
        assertTrue(diff.contains("--- f.txt") && diff.contains("differ only in line endings"), diff);
    }

    @Test
    void whitespaceOnlyAddedLineIsPrintedExactly() throws IOException {
        init();
        Files.writeString(dir.resolve("f.txt"), "a\n");
        cli.commitAll("base");
        Files.writeString(dir.resolve("f.txt"), "a\n   \n");

        assertTrue(cli.ok("diff").contains("+   \n"), "trailing spaces in the diff must be preserved");
    }

    @Test
    void unexpectedArgumentsAreRejectedInsteadOfIgnored() {
        init();
        cli.write("a.txt", "a");
        cli.ok("add", "a.txt");
        assertTrue(cli.fails("commit", "-m", "x", "extra").contains("unexpected argument 'extra'"));
        assertTrue(cli.fails("diff", "a.txt").contains("unexpected argument"));
        assertTrue(cli.fails("status", "foo").contains("unexpected argument"));
        assertTrue(cli.fails("log", "foo").contains("unexpected argument"));
        assertTrue(cli.fails("init", "a", "b").contains("unexpected argument"));
        assertTrue(cli.fails("branch", "a", "b").contains("unexpected argument"));
        assertTrue(cli.repo().refs().headCommit().isEmpty(), "a rejected commit must not create a commit");
    }

    @Test
    void emptyPathspecIsRejected() {
        init();
        assertTrue(cli.fails("add", "").contains("empty pathspec"));
    }

    @Test
    void commitMessageThatLooksLikeAFlagIsStillAMessage() {
        init();
        cli.write("a.txt", "a");
        cli.ok("add", "a.txt");

        cli.ok("commit", "-m", "-h");

        assertEquals("-h", cli.repo().headCommit().orElseThrow().message());
    }

    @Test
    void helpFlagStillWorksAnywhere() {
        init();
        assertTrue(cli.ok("commit", "--help").contains("usage: minigit commit"));
        assertTrue(cli.ok("checkout", "-h").contains("usage: minigit checkout"));
    }

    // ---------------- Unicode, spaces, empty files, nesting ----------------

    @Test
    void unicodeNamesSpacesEmptyFilesAndNestedDirectoriesSurviveCommitAndCheckout() throws IOException {
        init();
        byte[] content = "héllo wörld ✓ 日本語\n".getBytes(StandardCharsets.UTF_8);
        cli.write("a b.txt", "spaces\n");
        Files.createDirectories(dir.resolve("dir with space/ünï"));
        Files.write(dir.resolve("dir with space/ünï/файл 名前.txt"), content);
        Files.write(dir.resolve("empty.txt"), new byte[0]);
        cli.commitAll("first");

        cli.ok("checkout", "-b", "other");
        cli.delete("a b.txt");
        cli.delete("empty.txt");
        cli.commitAll("removed some");
        cli.ok("checkout", "main");

        assertEquals("spaces\n", cli.read("a b.txt"));
        assertEquals(0, Files.size(dir.resolve("empty.txt")));
        assertArrayEquals(content, Files.readAllBytes(dir.resolve("dir with space/ünï/файл 名前.txt")));
        assertTrue(cli.ok("status").contains("working tree clean"));
    }

    @Test
    void emptyFileCanBeAddedModifiedAndDiffed() throws IOException {
        init();
        Files.write(dir.resolve("empty.txt"), new byte[0]);
        cli.commitAll("empty");
        Files.writeString(dir.resolve("empty.txt"), "now has content\n");

        assertTrue(cli.ok("status").contains("modified: empty.txt"));
        assertTrue(cli.ok("diff").contains("+now has content"));
    }

    @Test
    void unicodeCommitMessageRoundTripsThroughTheApiAndLog() {
        init();
        cli.write("a.txt", "a");
        cli.ok("add", "a.txt");
        // Passed as a Java String (not through an OS command line), so no code-page conversion is involved.
        cli.ok("commit", "-m", "ünï ✓ 日本語");
        assertEquals("ünï ✓ 日本語", cli.repo().headCommit().orElseThrow().message());
        assertTrue(cli.ok("log").contains("ünï ✓ 日本語"));
    }

    @Test
    void addingAFileWithTheWrongLetterCaseStagesTheRealSpelling() {
        init();
        cli.write("README.md", "hi\n");
        Assumptions.assumeTrue(Files.exists(dir.resolve("readme.md")), "file system is case-sensitive");

        cli.ok("add", "readme.md");

        assertEquals(Set.of("README.md"), staged());
    }

    // ---------------- storage layer ----------------

    @Test
    void storedBlobMatchesItsHashEvenForLargeFiles() throws IOException {
        init();
        Path big = dir.resolve("big.bin");
        byte[] data = new byte[3 * 1024 * 1024 + 17];
        new java.util.Random(42).nextBytes(data);
        Files.write(big, data);

        cli.ok("add", "big.bin");

        Index index = cli.repo().loadIndex();
        String hash = index.get("big.bin").orElseThrow().hash();
        assertEquals(HashUtils.sha256(data), hash);
        assertArrayEquals(data, cli.repo().objects().readObject(hash));
    }

    @Test
    void noTemporaryFilesAreLeftBehindByNormalOperation() throws IOException {
        init();
        cli.write("a.txt", "a");
        cli.commitAll("c");
        cli.ok("checkout", "-b", "x");
        cli.write("b.txt", "b");
        cli.commitAll("d");
        cli.ok("checkout", "main");

        try (var files = Files.walk(dir)) {
            assertEquals(List.of(), files.filter(p -> p.getFileName().toString().startsWith(".minigit-tmp-")).toList());
        }
    }

    @Test
    void trackedMapOfAnEmptyRepositoryIsEmpty() {
        init();
        assertEquals(Map.of(), cli.repo().headTree());
    }
}
