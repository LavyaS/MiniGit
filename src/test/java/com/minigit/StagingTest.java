package com.minigit;

import com.minigit.core.Index;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StagingTest {

    @TempDir
    Path dir;

    private Cli cli;

    private Index index() {
        return cli.repo().loadIndex();
    }

    private void init() {
        cli = new Cli(dir).initialized();
    }

    @Test
    void addStoresBlobAndRecordsIndexEntry() {
        init();
        cli.write("README.md", "Hello MiniGit\n");

        assertEquals("Added README.md", cli.ok("add", "README.md").trim());

        String hash = index().get("README.md").orElseThrow().hash();
        assertTrue(cli.repo().objects().objectExists(hash));
        assertEquals("Hello MiniGit\n", new String(cli.repo().objects().readObject(hash)));
    }

    @Test
    void modifyingAStagedFileAndAddingAgainUpdatesTheEntry() {
        init();
        cli.write("a.txt", "one");
        cli.ok("add", "a.txt");
        String first = index().get("a.txt").orElseThrow().hash();

        cli.write("a.txt", "two");
        cli.ok("add", "a.txt");

        String second = index().get("a.txt").orElseThrow().hash();
        assertFalse(first.equals(second));
        assertEquals("two", new String(cli.repo().objects().readObject(second)));
    }

    @Test
    void addingSameSizeEditWithinTheSameInstantIsStillDetected() {
        init();
        cli.write("a.txt", "AAAA");
        cli.ok("add", "a.txt");
        cli.write("a.txt", "BBBB");
        cli.ok("add", "a.txt");
        assertEquals("BBBB", new String(cli.repo().objects().readObject(index().get("a.txt").orElseThrow().hash())));
    }

    @Test
    void multipleFilesCanBeStagedInOneCommand() {
        init();
        cli.write("a.txt", "A");
        cli.write("b.txt", "B");
        cli.ok("add", "a.txt", "b.txt");
        assertEquals(Set.of("a.txt", "b.txt"), index().paths());
    }

    @Test
    void addDotStagesNestedFilesRecursively() {
        init();
        cli.write("a.txt", "A");
        cli.write("src/main/App.java", "class App {}");
        cli.write("src/util/Helper.java", "class Helper {}");

        cli.ok("add", ".");

        assertEquals(Set.of("a.txt", "src/main/App.java", "src/util/Helper.java"), index().paths());
    }

    @Test
    void addDotNeverStagesTheMetadataDirectory() {
        init();
        cli.write("a.txt", "A");
        cli.ok("add", ".");
        assertTrue(index().paths().stream().noneMatch(p -> p.startsWith(".minigit")), index().paths().toString());
    }

    @Test
    void addingTheMetadataDirectoryExplicitlyIsRejected() {
        init();
        assertTrue(cli.fails("add", ".minigit").contains("metadata"));
    }

    @Test
    void addingAMissingFileReportsPathspecError() {
        init();
        assertEquals("fatal: pathspec 'abc.txt' did not match any files", cli.fails("add", "abc.txt").trim());
    }

    @Test
    void addDirectoryStagesOnlyThatDirectory() {
        init();
        cli.write("src/A.java", "A");
        cli.write("docs/readme.md", "R");
        cli.ok("add", "src");
        assertEquals(Set.of("src/A.java"), index().paths());
    }

    @Test
    void addWorksFromASubdirectoryUsingRepoRelativeStorage() {
        init();
        cli.write("src/A.java", "A");
        Cli inSrc = new Cli(dir.resolve("src"));
        inSrc.ok("add", "A.java");
        assertEquals(Set.of("src/A.java"), index().paths());
    }

    @Test
    void addWithoutArgumentsFails() {
        init();
        assertTrue(cli.fails("add").contains("nothing specified"));
    }

    @Test
    void deletedFileIsStagedAsRemovalByAddDot() {
        init();
        cli.write("a.txt", "A");
        cli.write("b.txt", "B");
        cli.commitAll("base");

        cli.delete("b.txt");
        String out = cli.ok("add", ".");

        assertTrue(out.contains("Removed b.txt"), out);
        assertEquals(Set.of("a.txt"), index().paths());
    }
}
