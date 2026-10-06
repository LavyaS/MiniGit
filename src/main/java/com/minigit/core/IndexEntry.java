package com.minigit.core;

import com.minigit.storage.FileStore;

import java.nio.file.Path;

/**
 * One staged file: its blob hash plus the size and modification time observed when it was staged.
 * The size/mtime pair lets later commands skip re-hashing files that have not been touched.
 * A size of {@link #UNKNOWN_SIZE} marks a "racy" entry whose fingerprint must not be trusted.
 */
public record IndexEntry(String hash, long size, long mtimeMillis) {

    public static final long UNKNOWN_SIZE = -1;

    public static IndexEntry forFile(String hash, Path file) {
        return new IndexEntry(hash, FileStore.size(file), FileStore.lastModifiedMillis(file));
    }

    /** True when the file still looks exactly like it did when it was staged. */
    public boolean matchesFingerprint(long currentSize, long currentMtimeMillis) {
        return size != UNKNOWN_SIZE && size == currentSize && mtimeMillis == currentMtimeMillis;
    }

    public IndexEntry smudged() {
        return new IndexEntry(hash, UNKNOWN_SIZE, mtimeMillis);
    }
}
