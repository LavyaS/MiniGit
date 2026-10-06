package com.minigit;

import com.minigit.core.StatusReport;
import com.minigit.core.StatusReport.ChangeType;
import com.minigit.core.StatusReport.FileChange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusTest {

    @TempDir
    Path dir;

    private Cli cli;

    private StatusReport report() {
        return StatusReport.compute(cli.repo());
    }

    private void init() {
        cli = new Cli(dir).initialized();
    }

    @Test
    void freshRepositoryIsCleanAndMentionsNoCommits() {
        init();
        String out = cli.ok("status");
        assertTrue(out.startsWith("On branch main"), out);
        assertTrue(out.contains("No commits yet"), out);
        assertTrue(out.contains("working tree clean"), out);
        assertTrue(report().isClean());
    }

    @Test
    void untrackedFileIsListed() {
        init();
        cli.write("notes.txt", "n");
        assertEquals(List.of("notes.txt"), report().untracked());
        assertTrue(cli.ok("status").contains("Untracked files:\n  notes.txt"));
    }

    @Test
    void newStagedFileIsReportedAsToBeCommitted() {
        init();
        cli.write("test.txt", "t");
        cli.ok("add", "test.txt");
        assertEquals(List.of(new FileChange("test.txt", ChangeType.NEW)), report().staged());
        assertTrue(cli.ok("status").contains("Changes to be committed:\n  new file: test.txt"));
    }

    @Test
    void modifiedUnstagedFileIsReported() {
        init();
        cli.write("app.txt", "v1");
        cli.commitAll("base");
        cli.write("app.txt", "v2");

        assertEquals(List.of(new FileChange("app.txt", ChangeType.MODIFIED)), report().unstaged());
        assertTrue(report().staged().isEmpty());
        assertTrue(cli.ok("status").contains("Changes not staged for commit:\n  modified: app.txt"));
    }

    @Test
    void modifiedAndStagedFileMovesToTheStagedSection() {
        init();
        cli.write("README.md", "v1");
        cli.commitAll("base");
        cli.write("README.md", "v2");
        cli.ok("add", "README.md");

        assertEquals(List.of(new FileChange("README.md", ChangeType.MODIFIED)), report().staged());
        assertTrue(report().unstaged().isEmpty());
    }

    @Test
    void deletedTrackedFileIsReportedAsUnstagedDeletion() {
        init();
        cli.write("gone.txt", "bye");
        cli.commitAll("base");
        cli.delete("gone.txt");

        assertEquals(List.of(new FileChange("gone.txt", ChangeType.DELETED)), report().unstaged());
        assertTrue(cli.ok("status").contains("deleted: gone.txt"));
    }

    @Test
    void stagedDeletionIsReportedUnderChangesToBeCommitted() {
        init();
        cli.write("gone.txt", "bye");
        cli.commitAll("base");
        cli.delete("gone.txt");
        cli.ok("add", ".");

        assertEquals(List.of(new FileChange("gone.txt", ChangeType.DELETED)), report().staged());
        assertTrue(report().unstaged().isEmpty());
    }

    @Test
    void cleanRepositoryAfterCommitHasNothingToReport() {
        init();
        cli.write("a.txt", "a");
        cli.commitAll("base");
        assertTrue(report().isClean());
        assertTrue(cli.ok("status").contains("nothing to commit, working tree clean"));
    }

    @Test
    void metadataDirectoryNeverShowsUpAsUntracked() {
        init();
        assertFalse(cli.ok("status").contains(".minigit"));
    }

    @Test
    void statusCombinesAllThreeSections() {
        init();
        cli.write("README.md", "v1");
        cli.write("app.txt", "v1");
        cli.commitAll("base");

        cli.write("README.md", "v2");
        cli.ok("add", "README.md");
        cli.write("test.txt", "new");
        cli.ok("add", "test.txt");
        cli.write("app.txt", "v2");
        cli.write("notes.txt", "n");

        String out = cli.ok("status");
        assertTrue(out.contains("Changes to be committed:\n  modified: README.md\n  new file: test.txt"), out);
        assertTrue(out.contains("Changes not staged for commit:\n  modified: app.txt"), out);
        assertTrue(out.contains("Untracked files:\n  notes.txt"), out);
    }

    @Test
    void touchingAFileWithoutChangingContentIsNotReportedAsModified() throws Exception {
        init();
        cli.write("a.txt", "same");
        cli.commitAll("base");
        Thread.sleep(20);
        cli.write("a.txt", "same");
        assertTrue(report().unstaged().isEmpty());
    }
}
