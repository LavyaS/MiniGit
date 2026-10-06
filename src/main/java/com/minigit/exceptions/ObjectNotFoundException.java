package com.minigit.exceptions;

/** Thrown when an object hash is not present in the object store. */
public class ObjectNotFoundException extends MiniGitException {

    public ObjectNotFoundException(String hash) {
        super("object " + hash + " does not exist in the object store");
    }
}
