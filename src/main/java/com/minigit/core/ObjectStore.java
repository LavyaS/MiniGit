package com.minigit.core;

import com.minigit.exceptions.MiniGitException;
import com.minigit.exceptions.ObjectNotFoundException;
import com.minigit.hash.HashUtils;
import com.minigit.storage.FileStore;

import java.nio.file.Path;
import java.security.MessageDigest;

/**
 * Content-addressable storage. An object's name is the SHA-256 of its content and it lives at
 * {@code objects/<first 2 hex chars>/<remaining 62 hex chars>}. Objects are immutable: an
 * object that already exists is never rewritten. Every read re-hashes the content, so a damaged
 * object is reported instead of being silently used.
 */
public class ObjectStore {

    private static final int FAN_OUT_LENGTH = 2;

    private final Path objectsDir;

    public ObjectStore(Path objectsDir) {
        this.objectsDir = objectsDir;
    }

    public String hashObject(byte[] content) {
        return HashUtils.sha256(content);
    }

    /** Hashes and stores {@code content}, returning its hash. */
    public String writeObject(byte[] content) {
        String hash = hashObject(content);
        writeObject(hash, content);
        return hash;
    }

    /**
     * Stores {@code content} under {@code hash}.
     *
     * @throws IllegalArgumentException if the hash is malformed or does not match the content
     */
    public void writeObject(String hash, byte[] content) {
        requireValidHash(hash);
        if (!hash.equals(hashObject(content))) {
            throw new IllegalArgumentException("hash " + hash + " does not match the content");
        }
        if (objectExists(hash)) {
            return;
        }
        FileStore.writeAtomic(pathFor(hash), content);
    }

    /**
     * Stores a file by streaming it (never fully loaded into memory) and returns its hash. The file is
     * read exactly once and the hash is computed from the bytes actually copied, so a file that is
     * modified while being staged can never end up stored under the wrong name.
     */
    public String storeFile(Path file) {
        MessageDigest digest = HashUtils.newDigest();
        Path temp = FileStore.copyToTemp(file, objectsDir, digest);
        String hash = HashUtils.toHex(digest.digest());
        if (objectExists(hash)) {
            FileStore.delete(temp);
        } else {
            FileStore.moveIntoPlace(temp, pathFor(hash));
        }
        return hash;
    }

    /** Reads an object and checks that its content still matches its name. */
    public byte[] readObject(String hash) {
        requireValidHash(hash);
        Path path = pathFor(hash);
        if (!FileStore.exists(path)) {
            throw new ObjectNotFoundException(hash);
        }
        byte[] content = FileStore.readBytes(path);
        if (!hash.equals(hashObject(content))) {
            throw corrupt(hash);
        }
        return content;
    }

    /** Fails unless the object exists and its content still matches its hash (streams the file). */
    public void verifyObject(String hash) {
        requireValidHash(hash);
        Path path = pathFor(hash);
        if (!FileStore.exists(path)) {
            throw new ObjectNotFoundException(hash);
        }
        if (!hash.equals(HashUtils.sha256(path))) {
            throw corrupt(hash);
        }
    }

    /** Streams an object to {@code destination}, replacing whatever is there. */
    public void copyObjectTo(String hash, Path destination) {
        requireValidHash(hash);
        Path path = pathFor(hash);
        if (!FileStore.exists(path)) {
            throw new ObjectNotFoundException(hash);
        }
        FileStore.copyAtomic(path, destination);
    }

    public boolean objectExists(String hash) {
        return HashUtils.isValidHash(hash) && FileStore.exists(pathFor(hash));
    }

    /** Location of an object on disk: {@code <objects>/<2 chars>/<62 chars>}. */
    public Path pathFor(String hash) {
        requireValidHash(hash);
        return objectsDir.resolve(hash.substring(0, FAN_OUT_LENGTH)).resolve(hash.substring(FAN_OUT_LENGTH));
    }

    private static void requireValidHash(String hash) {
        if (!HashUtils.isValidHash(hash)) {
            throw new MiniGitException("invalid object name '" + hash + "'");
        }
    }

    private static MiniGitException corrupt(String hash) {
        return new MiniGitException("object " + hash + " is corrupt: its content does not match its hash");
    }
}
