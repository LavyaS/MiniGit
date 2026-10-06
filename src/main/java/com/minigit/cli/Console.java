package com.minigit.cli;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/** Thin wrapper over the output streams so commands never touch {@code System.out} directly. */
public class Console {

    private final PrintStream out;
    private final PrintStream err;

    public Console(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    /** A console bound to the process's stdout/stderr using UTF-8. */
    public static Console system() {
        return new Console(
                new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8),
                new PrintStream(new FileOutputStream(FileDescriptor.err), true, StandardCharsets.UTF_8));
    }

    public void println(String line) {
        out.println(line);
    }

    public void println() {
        out.println();
    }

    public void error(String line) {
        err.println(line);
    }
}
