package com.minigit;

import com.minigit.diff.DiffEngine;
import com.minigit.diff.DiffResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiffAndCliTest {

    @TempDir
    Path dir;

    private final DiffEngine engine = new DiffEngine();

    // ---- diff engine ----

    @Test
    void addedLineIsDetected() {
        DiffResult result = engine.diff(List.of("a", "c"), List.of("a", "b", "c"));
        assertEquals(1, result.additions());
        assertEquals(0, result.deletions());
        assertTrue(result.format("f", "f").contains("+b"));
    }

    @Test
    void removedLineIsDetected() {
        DiffResult result = engine.diff(List.of("a", "b", "c"), List.of("a", "c"));
        assertEquals(0, result.additions());
        assertEquals(1, result.deletions());
        assertTrue(result.format("f", "f").contains("-b"));
    }

    @Test
    void modifiedLineIsShownAsRemovalPlusAddition() {
        String text = engine.diff(List.of("Hello world"), List.of("Hello MiniGit")).format("README.md", "README.md");
        assertTrue(text.startsWith("--- README.md\n+++ README.md\n"), text);
        assertTrue(text.contains("-Hello world\n+Hello MiniGit\n"), text);
    }

    @Test
    void identicalContentHasNoChanges() {
        DiffResult result = engine.diff("same\n".getBytes(StandardCharsets.UTF_8), "same\n".getBytes(StandardCharsets.UTF_8));
        assertFalse(result.hasChanges());
        assertEquals("", result.format("f", "f"));
    }

    @Test
    void binaryContentIsReportedWithoutLineDiff() {
        DiffResult result = engine.diff(new byte[]{1, 0, 2}, new byte[]{1, 0, 3});
        assertTrue(result.binary());
        assertTrue(result.format("img.png", "img.png").contains("Binary files differ"));
    }

    @Test
    void hunkHeadersCarryCorrectLineNumbersAndContext() {
        List<String> oldLines = List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
        List<String> newLines = List.of("1", "2", "3", "4", "5", "six", "7", "8", "9", "10");
        String text = engine.diff(oldLines, newLines).format("f", "f");
        assertTrue(text.contains("@@ -3,7 +3,7 @@"), text);
        assertTrue(text.contains("-6\n+six\n"), text);
        assertFalse(text.contains(" 1\n"), "lines outside the 3-line context must be omitted: " + text);
    }

    @Test
    void lineEndingOnlyDifferencesAreReportedNotHidden() {
        DiffResult result = engine.diff("a\r\nb\r\n".getBytes(StandardCharsets.UTF_8), "a\nb\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(result.hasChanges());
        assertEquals(0, result.additions());
        assertTrue(result.format("f", "f").contains("differ only in line endings"));
    }

    @Test
    void missingTrailingNewlineIsReportedNotHidden() {
        DiffResult result = engine.diff("a\nb\n".getBytes(StandardCharsets.UTF_8), "a\nb".getBytes(StandardCharsets.UTF_8));
        assertTrue(result.hasChanges());
    }

    @Test
    void emptyOldFileIsAllAdditions() {
        DiffResult result = engine.diff(List.of(), List.of("x", "y"));
        assertEquals(2, result.additions());
    }

    // ---- diff command ----

    @Test
    void diffShowsWorkingTreeChangesAgainstLastCommit() {
        Cli cli = new Cli(dir).initialized();
        cli.write("README.md", "Hello world\n");
        cli.commitAll("base");
        cli.write("README.md", "Hello MiniGit\n");

        String out = cli.ok("diff");

        assertTrue(out.contains("--- README.md"), out);
        assertTrue(out.contains("+++ README.md"), out);
        assertTrue(out.contains("-Hello world"), out);
        assertTrue(out.contains("+Hello MiniGit"), out);
    }

    @Test
    void diffCachedShowsOnlyStagedChanges() {
        Cli cli = new Cli(dir).initialized();
        cli.write("a.txt", "one\n");
        cli.write("b.txt", "one\n");
        cli.commitAll("base");
        cli.write("a.txt", "two\n");
        cli.write("b.txt", "two\n");
        cli.ok("add", "a.txt");

        String cached = cli.ok("diff", "--cached");

        assertTrue(cached.contains("+two"), cached);
        assertTrue(cached.contains("--- a.txt"), cached);
        assertFalse(cached.contains("b.txt"), cached);
    }

    @Test
    void diffOfCleanRepositoryPrintsNothing() {
        Cli cli = new Cli(dir).initialized();
        cli.write("a.txt", "x\n");
        cli.commitAll("base");
        assertEquals("", cli.ok("diff").strip());
        assertEquals("", cli.ok("diff", "--cached").strip());
    }

    @Test
    void diffReportsBinaryFiles() throws Exception {
        Cli cli = new Cli(dir).initialized();
        Files.write(dir.resolve("img.bin"), new byte[]{0, 1, 2, 3});
        cli.commitAll("base");
        Files.write(dir.resolve("img.bin"), new byte[]{0, 9, 9, 9});
        assertTrue(cli.ok("diff").contains("Binary files differ"));
    }

    @Test
    void diffShowsDeletedFilesAsRemovals() {
        Cli cli = new Cli(dir).initialized();
        cli.write("a.txt", "bye\n");
        cli.commitAll("base");
        cli.delete("a.txt");
        String out = cli.ok("diff");
        assertTrue(out.contains("+++ /dev/null"), out);
        assertTrue(out.contains("-bye"), out);
    }

    // ---- CLI behaviour ----

    @Test
    void versionIsPrinted() {
        assertEquals("MiniGit version 1.0.0", new Cli(dir).ok("--version").trim());
    }

    @Test
    void helpListsEveryCommand() {
        String help = new Cli(dir).ok("help");
        for (String command : List.of("init", "add", "status", "commit", "log", "diff", "branch", "checkout", "config")) {
            assertTrue(help.contains(command), command + " missing from help:\n" + help);
        }
    }

    @Test
    void helpForACommandShowsItsUsage() {
        Cli cli = new Cli(dir);
        assertTrue(cli.ok("help", "commit").contains("usage: minigit commit -m <message>"));
        assertTrue(cli.ok("add", "--help").contains("usage: minigit add"));
    }

    @Test
    void unknownCommandProducesAFriendlyError() {
        Cli.Result result = new Cli(dir).run("frobnicate");
        assertEquals(1, result.exit());
        assertTrue(result.err().contains("'frobnicate' is not a minigit command"));
    }

    @Test
    void unknownOptionProducesAFriendlyError() {
        Cli cli = new Cli(dir).initialized();
        assertTrue(cli.fails("status", "--bogus").contains("unknown option '--bogus'"));
    }

    @Test
    void userErrorsNeverContainJavaStackTraces() {
        Cli cli = new Cli(dir).initialized();
        for (String[] args : new String[][]{{"add", "nope"}, {"checkout", "nope"}, {"commit", "-m", "x"}, {"log"}}) {
            String err = cli.fails(args);
            assertTrue(err.startsWith("fatal: "), err);
            assertFalse(err.contains("\tat "), err);
        }
    }
}
