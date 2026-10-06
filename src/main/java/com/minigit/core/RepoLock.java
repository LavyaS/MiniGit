package com.minigit.core;

import com.minigit.exceptions.MiniGitException;
import com.minigit.storage.FileStore;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Cross-process mutual exclusion for commands that change the repository, so two simultaneous
 * {@code minigit add} runs cannot overwrite each other's index updates.
 *
 * <p>The lock is an operating-system file lock on {@code .minigit/lock}. The OS releases it
 * automatically when the owning process exits or crashes, so there is no stale lock to detect or
 * clean up (and nothing for a third process to "steal" by mistake). Contenders wait up to a timeout.
 * The (empty) lock file itself is left in place between runs.
 */
public final class RepoLock implements AutoCloseable {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    private static final String LOCK_FILE = "lock";
    private static final long POLL_MILLIS = 20;

    private final FileChannel channel;
    private final FileLock lock;

    private RepoLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    public static RepoLock acquire(Repository repo) {
        return acquire(repo, DEFAULT_TIMEOUT);
    }

    /**
     * @throws MiniGitException if another process (or thread) still holds the lock after {@code timeout}
     */
    public static RepoLock acquire(Repository repo, Duration timeout) {
        Path file = repo.metadataDir().resolve(LOCK_FILE);
        FileChannel channel = FileStore.openLockChannel(file);
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            while (true) {
                FileLock lock = tryLock(channel);
                if (lock != null) {
                    return new RepoLock(channel, lock);
                }
                if (System.nanoTime() - deadline > 0) {
                    throw new MiniGitException("another MiniGit process is using this repository "
                            + "(waited " + timeout.toSeconds() + "s for " + file + "); try again when it has finished");
                }
                pause();
            }
        } catch (RuntimeException e) {
            closeQuietly(channel);
            throw e;
        }
    }

    @Override
    public void close() {
        try {
            lock.release();
        } catch (IOException ignored) {
            // closing the channel below releases the lock anyway
        } finally {
            closeQuietly(channel);
        }
    }

    /** Returns the lock, or null if it is currently held by another process or by another thread of this JVM. */
    private static FileLock tryLock(FileChannel channel) {
        try {
            return channel.tryLock();
        } catch (OverlappingFileLockException heldInThisJvm) {
            return null;
        } catch (IOException e) {
            throw new MiniGitException("unable to lock the repository: " + e.getMessage(), e);
        }
    }

    private static void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // nothing useful to do
        }
    }

    private static void pause() {
        try {
            Thread.sleep(POLL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MiniGitException("interrupted while waiting for the repository lock");
        }
    }
}
