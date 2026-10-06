package com.minigit;

import com.minigit.core.Repository;
import com.minigit.exceptions.RepositoryNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryAndConfigTest {

    @TempDir
    Path dir;

    @Test
    void initCreatesTheExpectedStructure() throws Exception {
        Cli cli = new Cli(dir);
        String out = cli.ok("init");

        Path meta = dir.resolve(".minigit");
        assertTrue(out.startsWith("Initialized empty MiniGit repository in "), out);
        assertTrue(out.trim().endsWith(meta.toString()), out);
        assertTrue(Files.isRegularFile(meta.resolve("HEAD")));
        assertTrue(Files.isRegularFile(meta.resolve("config")));
        assertTrue(Files.isRegularFile(meta.resolve("index")));
        assertTrue(Files.isDirectory(meta.resolve("objects")));
        assertTrue(Files.isDirectory(meta.resolve("logs")));
        assertTrue(Files.isRegularFile(meta.resolve("refs").resolve("heads").resolve("main")));
        assertEquals("ref: refs/heads/main", Files.readString(meta.resolve("HEAD")).strip());
    }

    @Test
    void initOnExistingRepositoryReportsItAndKeepsData() {
        Cli cli = new Cli(dir).initialized();
        cli.write("a.txt", "A");
        cli.commitAll("first");
        String head = cli.repo().refs().headCommit().orElseThrow();

        String out = cli.ok("init");

        assertTrue(out.contains("already exists"), out);
        assertEquals(head, cli.repo().refs().headCommit().orElseThrow());
    }

    @Test
    void repositoryIsDetectedFromASubdirectory() throws Exception {
        new Cli(dir).ok("init");
        Path nested = Files.createDirectories(dir.resolve("a").resolve("b"));
        assertEquals(dir.toAbsolutePath().normalize(), Repository.open(nested).root());
    }

    @Test
    void openingOutsideARepositoryFails() {
        assertFalse(Repository.exists(dir));
        assertThrows(RepositoryNotFoundException.class, () -> Repository.open(dir));
    }

    @Test
    void commandsOutsideARepositoryReportAFriendlyError() {
        String err = new Cli(dir).fails("status");
        assertTrue(err.startsWith("fatal: not a MiniGit repository"), err);
        assertFalse(err.contains("Exception"), err);
    }

    @Test
    void configValuesAreStoredAndReadBack() {
        Cli cli = new Cli(dir);
        cli.ok("init");
        cli.ok("config", "user.name", "Lavya");
        assertEquals("Lavya", cli.ok("config", "user.name").trim());
        assertTrue(cli.read(".minigit/config").contains("user.name=Lavya"));
    }

    @Test
    void commitWithoutIdentityExplainsHowToConfigureIt() {
        Cli cli = new Cli(dir);
        cli.ok("init");
        cli.write("a.txt", "A");
        cli.ok("add", "a.txt");
        String err = cli.fails("commit", "-m", "x");
        assertTrue(err.contains("minigit config user.name"), err);
        assertTrue(err.contains("minigit config user.email"), err);
    }

    @Test
    void invalidConfigKeyIsRejected() {
        Cli cli = new Cli(dir);
        cli.ok("init");
        assertTrue(cli.fails("config", "bogus", "value").contains("invalid config key"));
    }
}
