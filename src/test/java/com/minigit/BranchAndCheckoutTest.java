package com.minigit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BranchAndCheckoutTest {

    @TempDir
    Path dir;

    private Cli cli;

    /** One commit on main containing a.txt = "main". */
    private void initWithCommit() {
        cli = new Cli(dir).initialized();
        cli.write("a.txt", "main\n");
        cli.commitAll("first");
    }

    @Test
    void branchListMarksTheCurrentBranch() {
        initWithCommit();
        cli.ok("branch", "feature");
        assertEquals("  feature\n* main", cli.ok("branch").stripTrailing());
    }

    @Test
    void newBranchPointsAtTheCurrentCommit() {
        initWithCommit();
        cli.ok("branch", "feature");
        assertEquals(cli.repo().refs().headCommit(), cli.repo().refs().resolveBranch("feature"));
    }

    @Test
    void creatingABranchOnlyWritesOneSmallPointerFile() throws Exception {
        initWithCommit();
        long objectsBefore = java.nio.file.Files.walk(dir.resolve(".minigit/objects")).filter(java.nio.file.Files::isRegularFile).count();

        cli.ok("branch", "feature");

        long objectsAfter = java.nio.file.Files.walk(dir.resolve(".minigit/objects")).filter(java.nio.file.Files::isRegularFile).count();
        assertEquals(objectsBefore, objectsAfter, "no objects may be copied");
        assertEquals(cli.repo().headTree(), cli.repo().branchTree("feature"));
        assertEquals(64, cli.read(".minigit/refs/heads/feature").strip().length());
    }

    @Test
    void duplicateBranchIsRejected() {
        initWithCommit();
        cli.ok("branch", "feature");
        assertEquals("fatal: branch 'feature' already exists", cli.fails("branch", "feature").trim());
    }

    @Test
    void invalidBranchNameIsRejected() {
        initWithCommit();
        assertTrue(cli.fails("branch", "bad name").contains("not a valid branch name"));
        assertTrue(cli.fails("branch", "../escape").contains("not a valid branch name"));
    }

    @Test
    void branchBeforeFirstCommitExplainsTheProblem() {
        cli = new Cli(dir).initialized();
        assertTrue(cli.fails("branch", "feature").contains("no commits yet"));
    }

    @Test
    void checkoutSwitchesHeadAndPrintsConfirmation() {
        initWithCommit();
        cli.ok("branch", "feature");
        assertEquals("Switched to branch 'feature'", cli.ok("checkout", "feature").trim());
        assertEquals("feature", cli.repo().refs().currentBranch());
    }

    @Test
    void checkoutDashBCreatesAndSwitches() {
        initWithCommit();
        assertEquals("Switched to a new branch 'dev'", cli.ok("checkout", "-b", "dev").trim());
        assertEquals("dev", cli.repo().refs().currentBranch());
        assertEquals(cli.repo().refs().resolveBranch("main"), cli.repo().refs().resolveBranch("dev"));
    }

    @Test
    void checkoutUnknownBranchFails() {
        initWithCommit();
        assertEquals("fatal: branch 'unknown' does not exist", cli.fails("checkout", "unknown").trim());
    }

    @Test
    void checkoutRestoresEachBranchesFiles() {
        initWithCommit();
        cli.ok("checkout", "-b", "feature");
        cli.write("a.txt", "feature\n");
        cli.write("only-feature.txt", "x\n");
        cli.commitAll("feature work");

        cli.ok("checkout", "main");
        assertEquals("main\n", cli.read("a.txt"));
        assertFalse(cli.exists("only-feature.txt"));

        cli.ok("checkout", "feature");
        assertEquals("feature\n", cli.read("a.txt"));
        assertTrue(cli.exists("only-feature.txt"));
    }

    @Test
    void checkoutRestoresFilesDeletedOnTheOtherBranchAndPrunesEmptyDirectories() {
        initWithCommit();
        cli.ok("checkout", "-b", "feature");
        cli.write("deep/dir/file.txt", "deep\n");
        cli.commitAll("add deep file");

        cli.ok("checkout", "main");
        assertFalse(cli.exists("deep"), "empty directories should be removed");

        cli.ok("checkout", "feature");
        assertEquals("deep\n", cli.read("deep/dir/file.txt"));
    }

    @Test
    void checkoutLeavesStatusCleanAfterSwitching() {
        initWithCommit();
        cli.ok("checkout", "-b", "feature");
        cli.write("b.txt", "b\n");
        cli.commitAll("b");
        cli.ok("checkout", "main");
        assertTrue(cli.ok("status").contains("working tree clean"));
    }

    @Test
    void checkoutRefusesToOverwriteUnstagedChanges() {
        initWithCommit();
        cli.ok("checkout", "-b", "feature");
        cli.write("a.txt", "feature version\n");
        cli.commitAll("feature edit");
        cli.ok("checkout", "main");

        cli.write("a.txt", "precious local edit\n");
        String err = cli.fails("checkout", "feature");

        assertTrue(err.startsWith("fatal: uncommitted changes would be overwritten by checkout"), err);
        assertTrue(err.contains("a.txt"), err);
        assertEquals("precious local edit\n", cli.read("a.txt"));
        assertEquals("main", cli.repo().refs().currentBranch());
    }

    @Test
    void checkoutRefusesToOverwriteStagedChanges() {
        initWithCommit();
        cli.ok("checkout", "-b", "feature");
        cli.write("a.txt", "feature version\n");
        cli.commitAll("feature edit");
        cli.ok("checkout", "main");

        cli.write("a.txt", "staged edit\n");
        cli.ok("add", "a.txt");

        assertTrue(cli.fails("checkout", "feature").contains("would be overwritten"));
        assertEquals("staged edit\n", cli.read("a.txt"));
    }

    @Test
    void checkoutRefusesToClobberAnUntrackedFileInTheWay() {
        initWithCommit();
        cli.ok("checkout", "-b", "feature");
        cli.write("new.txt", "tracked on feature\n");
        cli.commitAll("add new");
        cli.ok("checkout", "main");

        cli.write("new.txt", "untracked local\n");
        assertTrue(cli.fails("checkout", "feature").contains("new.txt"));
        assertEquals("untracked local\n", cli.read("new.txt"));
    }

    @Test
    void unrelatedLocalChangesCarryOverToTheOtherBranch() {
        initWithCommit();
        cli.write("other.txt", "o\n");
        cli.commitAll("other");
        cli.ok("checkout", "-b", "feature");
        cli.write("a.txt", "feature\n");
        cli.commitAll("feature edit");

        cli.write("other.txt", "local edit to a file the branches share\n");
        cli.ok("checkout", "main");

        assertEquals("main\n", cli.read("a.txt"));
        assertEquals("local edit to a file the branches share\n", cli.read("other.txt"));
    }

    @Test
    void checkingOutTheCurrentBranchIsANoOp() {
        initWithCommit();
        assertEquals("Already on 'main'", cli.ok("checkout", "main").trim());
    }

    @Test
    void checkoutIsRecordedInTheHeadLog() {
        initWithCommit();
        cli.ok("checkout", "-b", "feature");
        assertTrue(cli.read(".minigit/logs/HEAD").contains("checkout: moving to feature"));
    }
}
