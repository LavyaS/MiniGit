package com.minigit.exceptions;

/** Thrown when a command that needs a repository runs outside of one. */
public class RepositoryNotFoundException extends MiniGitException {

    public RepositoryNotFoundException() {
        super("not a MiniGit repository (or any of the parent directories): run 'minigit init' first");
    }
}
