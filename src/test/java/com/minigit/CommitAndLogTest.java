package com.minigit;

import com.minigit.core.Commit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommitAndLogTest {

    @TempDir
    Path dir;

    private Cli cli;

    private void init() {
        cli = new Cli(dir).initialized();
    }

    @Test
    void initialCommitHasNoParentAndRecordsTheSnapshot() {
        init();
        cli.write("a.txt", "A");
        cli.write("src/b.txt", "B");
        cli.ok("add", ".");

        String out = cli.ok("commit", "-m", "Initial commit");

        Commit commit = cli.repo().headCommit().orElseThrow();
        assertNull(commit.parent());
        assertEquals("Initial commit", commit.message());
        assertEquals("Tester <tester@example.com>", commit.author());
        assertEquals(List.of("a.txt", "src/b.txt"), List.copyOf(commit.tree().keySet()));
        assertTrue(out.startsWith("[main " + commit.shortHash() + "] Initial commit"), out);
        assertTrue(out.contains("2 files changed"), out);
    }

    @Test
    void secondCommitPointsToTheFirst() {
        init();
        cli.write("a.txt", "A");
        cli.commitAll("first");
        String first = cli.repo().refs().headCommit().orElseThrow();

        cli.write("a.txt", "A2");
        cli.commitAll("second");

        Commit second = cli.repo().headCommit().orElseThrow();
        assertEquals(first, second.parent());
        assertFalse(first.equals(second.hash()));
    }

    @Test
    void commitIsStoredAsAnObjectAndHashMatchesItsContent() {
        init();
        cli.write("a.txt", "A");
        cli.commitAll("first");
        Commit commit = cli.repo().headCommit().orElseThrow();

        byte[] stored = cli.repo().objects().readObject(commit.hash());
        assertEquals(commit.hash(), cli.repo().objects().hashObject(stored));
        assertEquals(commit, Commit.parse(commit.hash(), new String(stored)));
    }

    @Test
    void commitSerializationRoundTripsMultilineMessagesAndAwkwardPaths() {
        String blob = "a".repeat(64);
        Commit original = Commit.create(null, "Me <me@x.io>", 123L, "subject\n\nbody line",
                Map.of("dir/with space.txt", blob));
        Commit parsed = Commit.parse(original.hash(), original.serialize());
        assertEquals(original, parsed);
        assertEquals("subject", parsed.subject());
    }

    @Test
    void emptyCommitIsRejected() {
        init();
        cli.write("a.txt", "A");
        cli.commitAll("first");
        assertEquals("fatal: nothing to commit", cli.fails("commit", "-m", "again").trim());
    }

    @Test
    void commitWithNothingEverStagedIsRejected() {
        init();
        assertEquals("fatal: nothing to commit", cli.fails("commit", "-m", "x").trim());
    }

    @Test
    void commitWithoutMessageIsRejected() {
        init();
        cli.write("a.txt", "A");
        cli.ok("add", "a.txt");
        assertTrue(cli.fails("commit").contains("no commit message"));
    }

    @Test
    void stagingAreaIsClearAfterCommit() {
        init();
        cli.write("a.txt", "A");
        cli.commitAll("first");
        String status = cli.ok("status");
        assertFalse(status.contains("Changes to be committed"), status);
        assertTrue(status.contains("working tree clean"), status);
    }

    @Test
    void deletedFileIsRemovedFromTheNextSnapshot() {
        init();
        cli.write("a.txt", "A");
        cli.write("b.txt", "B");
        cli.commitAll("both");

        cli.delete("b.txt");
        cli.commitAll("drop b");

        assertEquals(List.of("a.txt"), List.copyOf(cli.repo().headCommit().orElseThrow().tree().keySet()));
    }

    @Test
    void unchangedFilesKeepTheirBlobAcrossCommits() {
        init();
        cli.write("a.txt", "A");
        cli.write("b.txt", "B");
        cli.commitAll("one");
        String blobA = cli.repo().headTree().get("a.txt");

        cli.write("b.txt", "B2");
        cli.commitAll("two");

        assertEquals(blobA, cli.repo().headTree().get("a.txt"));
    }

    @Test
    void logListsCommitsNewestFirstFollowingParents() {
        init();
        cli.write("a.txt", "1");
        cli.commitAll("first");
        cli.write("a.txt", "2");
        cli.commitAll("second");
        cli.write("a.txt", "3");
        cli.commitAll("third");

        String log = cli.ok("log");

        assertTrue(log.indexOf("third") < log.indexOf("second"), log);
        assertTrue(log.indexOf("second") < log.indexOf("first"), log);
        assertTrue(log.contains("Author: Tester <tester@example.com>"), log);
        assertTrue(log.contains("Date:   "), log);
        assertEquals(3, log.lines().filter(l -> l.startsWith("commit ")).count());
    }

    @Test
    void logOnelineShowsShortHashAndSubject() {
        init();
        cli.write("a.txt", "1");
        cli.commitAll("first");
        String firstShort = cli.repo().headCommit().orElseThrow().shortHash();
        cli.write("a.txt", "2");
        cli.commitAll("second");
        String secondShort = cli.repo().headCommit().orElseThrow().shortHash();

        assertEquals(secondShort + " second\n" + firstShort + " first", cli.ok("log", "--oneline").strip()
                .replace("\r\n", "\n"));
    }

    @Test
    void logWithoutCommitsExplainsWhy() {
        init();
        assertTrue(cli.fails("log").contains("does not have any commits yet"));
    }

    @Test
    void commitsMadeInQuickSuccessionGetDistinctHashes() {
        init();
        cli.write("a.txt", "1");
        cli.commitAll("same message");
        String first = cli.repo().refs().headCommit().orElseThrow();
        cli.write("a.txt", "2");
        cli.commitAll("same message");
        assertFalse(first.equals(cli.repo().refs().headCommit().orElseThrow()));
    }
}
