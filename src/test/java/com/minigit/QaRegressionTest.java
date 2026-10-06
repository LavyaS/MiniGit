package com.minigit;

import com.minigit.core.RepoLock;
import com.minigit.core.Repository;
import com.minigit.exceptions.MiniGitException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression tests for bugs found by the adversarial QA pass (see QA matrix in the project history). */
class QaRegressionTest {

    @TempDir
    Path dir;

    private Cli cli;

    private void init() {
        cli = new Cli(dir).initialized();
    }

    // ---- BUG: a missing index was read as "nothing staged" and the next commit deleted every file ----

    @Test
    void missingIndexIsReportedAsDamageInsteadOfBeingTreatedAsEmpty() throws IOException {
        init();
        cli.write("a.txt", "a\n");
        cli.write("b.txt", "b\n");
        cli.commitAll("base");
        String head = cli.repo().refs().headCommit().orElseThrow();
        Files.delete(dir.resolve(".minigit/index"));

        for (String[] command : new String[][]{{"status"}, {"add", "."}, {"commit", "-m", "x"}, {"diff"}}) {
            String err = cli.fails(command);
            assertTrue(err.contains("index file is missing"), String.join(" ", command) + ": " + err);
        }
        assertEquals(head, cli.repo().refs().headCommit().orElseThrow(), "no commit may have been created");
        assertEquals(Set.of("a.txt", "b.txt"), cli.repo().headTree().keySet());
    }

    @Test
    void anEmptyButPresentIndexIsStillAValidState() throws IOException {
        init();
        cli.write("a.txt", "a\n");
        cli.commitAll("base");
        cli.delete("a.txt");
        cli.commitAll("delete everything");
        assertEquals("", Files.readString(dir.resolve(".minigit/index")));
        assertTrue(cli.ok("status").contains("working tree clean"));
    }

    // ---- BUG: two simultaneous commands overwrote each other's index changes (8 parallel adds kept 1) ----

    @Test
    void parallelAddsOfDifferentFilesAreAllRecorded() throws Exception {
        init();
        int workers = 12;
        for (int i = 0; i < workers; i++) {
            cli.write("p" + i + ".txt", "content " + i + "\n");
        }
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                String file = "p" + i + ".txt";
                Callable<Integer> add = () -> new Cli(dir).run("add", file).exit();
                results.add(pool.submit(add));
            }
            for (Future<Integer> result : results) {
                assertEquals(0, result.get());
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(workers, cli.repo().loadIndex().paths().size(), "an index update was lost");
        try (RepoLock free = RepoLock.acquire(cli.repo(), Duration.ofMillis(500))) {
            assertTrue(Files.exists(dir.resolve(".minigit/lock")), "the lock must be free again after all commands finished");
        }
    }

    @Test
    void parallelCommitsNeverForkHistory() throws Exception {
        init();
        cli.write("base.txt", "base\n");
        cli.commitAll("base");
        int workers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                int n = i;
                results.add(pool.submit(() -> {
                    Cli worker = new Cli(dir);
                    worker.write("w" + n + ".txt", "w" + n + "\n");
                    int add = worker.run("add", "w" + n + ".txt").exit();
                    int commit = worker.run("commit", "-m", "commit " + n).exit();
                    return add + commit;
                }));
            }
            for (Future<Integer> result : results) {
                result.get();
            }
        } finally {
            pool.shutdownNow();
        }

        // Every successful commit must be an ancestor chain: no commit may have been silently dropped.
        long commits = cli.ok("log", "--oneline").lines().count();
        long messages = cli.ok("log").lines().filter(l -> l.startsWith("    commit ")).count();
        assertEquals(messages + 1, commits, "history must be one linear chain");
        assertTrue(commits >= 2);
    }

    @Test
    void leftoverLockFileFromACrashedProcessNeverBlocksAnyone() throws IOException {
        init();
        // The OS drops a dead process's lock, so whatever a crash left in the file is irrelevant.
        for (String leftover : new String[]{"999999999", "not a pid", ""}) {
            Files.writeString(dir.resolve(".minigit/lock"), leftover);
            cli.write("a.txt", "a" + leftover + "\n");
            assertEquals(0, cli.run("add", "a.txt").exit(), "leftover: '" + leftover + "'");
        }
    }

    @Test
    void lockHeldElsewhereTimesOutWithAClearMessageAndIsReleasedAfterwards() {
        init();
        Repository repo = cli.repo();
        try (RepoLock held = RepoLock.acquire(repo)) {
            MiniGitException e = assertThrows(MiniGitException.class,
                    () -> RepoLock.acquire(repo, Duration.ofMillis(200)));
            assertTrue(e.getMessage().contains("another MiniGit process"), e.getMessage());
        }
        try (RepoLock again = RepoLock.acquire(repo, Duration.ofMillis(200))) {
            assertTrue(Files.exists(dir.resolve(".minigit/lock")));
        }
    }

    @Test
    void aCommandWaitsForAHeldLockInsteadOfFailing() throws Exception {
        init();
        cli.write("a.txt", "a\n");
        Repository repo = cli.repo();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> add;
            try (RepoLock held = RepoLock.acquire(repo)) {
                add = pool.submit(() -> new Cli(dir).run("add", "a.txt").exit());
                Thread.sleep(300);
                assertFalse(add.isDone(), "the command must be waiting for the lock");
            }
            assertEquals(0, add.get(10, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        assertEquals(Set.of("a.txt"), cli.repo().loadIndex().paths(), "the waiting command ran once the lock was free");
    }

    @Test
    void lockIsReleasedWhenTheCommandFails() {
        init();
        cli.fails("add", "does-not-exist.txt");
        try (RepoLock free = RepoLock.acquire(cli.repo(), Duration.ofMillis(500))) {
            assertTrue(Files.exists(dir.resolve(".minigit/lock")));
        }
    }

    @Test
    void addDotIgnoresTheLockFile() {
        init();
        cli.write("a.txt", "a\n");
        cli.write("z.txt", "z\n");
        cli.ok("add", ".");
        assertEquals(Set.of("a.txt", "z.txt"), cli.repo().loadIndex().paths());
    }

    // ---- scale sanity: many files across many directories must round-trip correctly (and not blow up) ----

    @Test
    void manyFilesAcrossManyDirectoriesRoundTripThroughAddCommitAndCheckout() {
        init();
        for (int i = 0; i < 800; i++) {
            cli.write("many/dir" + (i % 25) + "/file" + i + ".txt", "content " + i + "\n");
        }
        assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
            cli.ok("add", ".");
            cli.ok("commit", "-m", "many");
            cli.ok("checkout", "-b", "other");
            for (int i = 0; i < 800; i += 2) {
                cli.write("many/dir" + (i % 25) + "/file" + i + ".txt", "changed " + i + "\n");
            }
            cli.commitAll("changed half");
            cli.ok("checkout", "main");
            cli.ok("checkout", "other");
        });
        assertEquals("changed 400\n", cli.read("many/dir0/file400.txt"));
        assertEquals("content 401\n", cli.read("many/dir1/file401.txt"));
        assertTrue(cli.ok("status").contains("working tree clean"));
    }

    // ---- previously untested: checkout rollback when a file is exclusively locked by another process ----

    @Test
    void checkoutOverAnExclusivelyLockedFileIsRolledBackAndLeavesAConsistentState() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"), "Windows file locking only");
        init();
        cli.write("keep.txt", "keep\n");
        cli.write("locked.txt", "v1\n");
        cli.commitAll("base");
        cli.ok("checkout", "-b", "other");
        cli.write("locked.txt", "v2\n");
        cli.write("added.txt", "new\n");
        cli.commitAll("other");
        cli.ok("checkout", "main");
        // Age the file and re-stage it so its fingerprint is trusted: the checkout then gets past the
        // preflight (which would otherwise have to read the locked file) and fails while applying.
        Files.setLastModifiedTime(dir.resolve("locked.txt"), java.nio.file.attribute.FileTime.fromMillis(1_000_000_000L));
        cli.ok("add", ".");

        Process holder = new ProcessBuilder("powershell", "-NoProfile", "-Command",
                "$f=[IO.File]::Open('" + dir.resolve("locked.txt") + "','Open','Read','None'); Start-Sleep -Seconds 12; $f.Close()")
                .redirectErrorStream(true).start();
        try {
            Thread.sleep(3000);
            Assumptions.assumeTrue(holder.isAlive(), "could not start the lock holder");

            String err = cli.fails("checkout", "other");

            assertTrue(err.contains("rolled back"), err);
            assertEquals("main", cli.repo().refs().currentBranch());
            assertFalse(cli.exists("added.txt"), "files restored before the failure must be undone");
        } finally {
            holder.destroyForcibly();
            holder.waitFor();
        }
        assertEquals("v1\n", cli.read("locked.txt"));
        assertTrue(cli.ok("status").contains("working tree clean"));
        cli.ok("checkout", "other");
        assertEquals("v2\n", cli.read("locked.txt"));
    }
}
