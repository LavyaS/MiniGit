package com.minigit;

import com.minigit.cli.CommandHandler;
import com.minigit.cli.Console;
import com.minigit.commands.CommandRegistry;
import com.minigit.core.Repository;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Test helper: runs real MiniGit commands against a temporary directory and captures their output. */
public final class Cli {

    public record Result(int exit, String out, String err) {
    }

    private final Path dir;

    public Cli(Path dir) {
        this.dir = dir;
    }

    public Path dir() {
        return dir;
    }

    public Result run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        Console console = new Console(new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
        int exit = new CommandHandler(console, dir, CommandRegistry.defaultCommands()).run(args);
        return new Result(exit, normalize(out.toString(StandardCharsets.UTF_8)), normalize(err.toString(StandardCharsets.UTF_8)));
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n");
    }

    /** Runs a command that must succeed and returns its stdout. */
    public String ok(String... args) {
        Result result = run(args);
        assertEquals(0, result.exit(), "command failed: " + String.join(" ", args) + "\n" + result.err());
        return result.out();
    }

    /** Runs a command that must fail and returns its stderr. */
    public String fails(String... args) {
        Result result = run(args);
        assertEquals(1, result.exit(), "command should have failed: " + String.join(" ", args));
        return result.err();
    }

    /** {@code init} + identity configuration. */
    public Cli initialized() {
        ok("init");
        ok("config", "user.name", "Tester");
        ok("config", "user.email", "tester@example.com");
        return this;
    }

    public Path write(String relative, String content) {
        try {
            Path file = dir.resolve(relative);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
            return file;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public String read(String relative) {
        try {
            return Files.readString(dir.resolve(relative));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean exists(String relative) {
        return Files.exists(dir.resolve(relative));
    }

    public void delete(String relative) {
        try {
            Files.delete(dir.resolve(relative));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public Repository repo() {
        return Repository.open(dir);
    }

    /** Stages everything and commits with the given message. */
    public void commitAll(String message) {
        ok("add", ".");
        ok("commit", "-m", message);
    }
}
