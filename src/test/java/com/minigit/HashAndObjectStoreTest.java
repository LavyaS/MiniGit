package com.minigit;

import com.minigit.core.ObjectStore;
import com.minigit.exceptions.MiniGitException;
import com.minigit.exceptions.ObjectNotFoundException;
import com.minigit.hash.HashUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HashAndObjectStoreTest {

    @TempDir
    Path dir;

    private ObjectStore store;

    @BeforeEach
    void setUp() {
        store = new ObjectStore(dir.resolve("objects"));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    // ---- hashing ----

    @Test
    void sameContentProducesSameHash() {
        assertEquals(HashUtils.sha256(bytes("hello")), HashUtils.sha256(bytes("hello")));
    }

    @Test
    void differentContentProducesDifferentHash() {
        assertNotEquals(HashUtils.sha256(bytes("hello")), HashUtils.sha256(bytes("hello!")));
    }

    @Test
    void hashMatchesKnownSha256Vector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                HashUtils.sha256(bytes("abc")));
    }

    @Test
    void fileHashEqualsContentHash() throws IOException {
        Path file = dir.resolve("f.txt");
        Files.writeString(file, "streamed content\n".repeat(5000));
        assertEquals(HashUtils.sha256(Files.readAllBytes(file)), HashUtils.sha256(file));
    }

    @Test
    void hashValidationAcceptsOnly64LowercaseHexCharacters() {
        assertTrue(HashUtils.isValidHash(HashUtils.sha256(bytes("x"))));
        assertFalse(HashUtils.isValidHash("abc"));
        assertFalse(HashUtils.isValidHash("Z".repeat(64)));
        assertFalse(HashUtils.isValidHash(null));
    }

    // ---- object store ----

    @Test
    void writtenObjectCanBeReadBack() {
        byte[] content = bytes("blob content");
        String hash = store.writeObject(content);
        assertArrayEquals(content, store.readObject(hash));
    }

    @Test
    void objectExistsOnlyAfterItIsWritten() {
        byte[] content = bytes("exists?");
        String hash = store.hashObject(content);
        assertFalse(store.objectExists(hash));
        store.writeObject(hash, content);
        assertTrue(store.objectExists(hash));
    }

    @Test
    void objectIsStoredUnderTwoCharDirectoryAndRemainingHash() {
        String hash = store.writeObject(bytes("layout"));
        Path expected = dir.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash.substring(2));
        assertEquals(expected, store.pathFor(hash));
        assertTrue(Files.isRegularFile(expected));
        assertEquals(62, expected.getFileName().toString().length());
    }

    @Test
    void existingObjectIsNotRewritten() throws IOException {
        byte[] content = bytes("immutable");
        String hash = store.writeObject(content);
        Path file = store.pathFor(hash);
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_000_000L));
        store.writeObject(hash, content);
        assertEquals(1_000_000L, Files.getLastModifiedTime(file).toMillis());
    }

    @Test
    void writingWithWrongHashIsRejected() {
        String wrong = HashUtils.sha256(bytes("something else"));
        assertThrows(IllegalArgumentException.class, () -> store.writeObject(wrong, bytes("content")));
    }

    @Test
    void readingMissingObjectFails() {
        assertThrows(ObjectNotFoundException.class, () -> store.readObject(HashUtils.sha256(bytes("nope"))));
    }

    @Test
    void malformedHashIsRejected() {
        assertThrows(MiniGitException.class, () -> store.readObject("not-a-hash"));
    }

    @Test
    void storeFileStreamsContentIntoTheStore() throws IOException {
        Path file = dir.resolve("big.txt");
        Files.writeString(file, "line\n".repeat(10_000));
        String hash = store.storeFile(file);
        assertEquals(HashUtils.sha256(Files.readAllBytes(file)), hash);
        assertArrayEquals(Files.readAllBytes(file), store.readObject(hash));
    }
}
