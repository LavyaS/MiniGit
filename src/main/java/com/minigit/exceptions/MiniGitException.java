package com.minigit.exceptions;

/**
 * Base class for every expected, user-facing MiniGit failure.
 * The CLI prints the message as {@code fatal: <message>} without a stack trace.
 */
public class MiniGitException extends RuntimeException {

    public MiniGitException(String message) {
        super(message);
    }

    public MiniGitException(String message, Throwable cause) {
        super(message, cause);
    }
}
