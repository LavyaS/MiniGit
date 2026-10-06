package com.minigit.storage;

import com.minigit.exceptions.MiniGitException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * The only class that talks to the file system directly.
 * Every {@link IOException} is translated into a {@link MiniGitException}, and all
 * writes go through a temporary file followed by a move so readers never see a partial file.
 * Symbolic links are never followed when deciding whether something is a regular file.
 */
public final class FileStore {

    private static final String TEMP_PREFIX = ".minigit-tmp-";
    private static final String TEMP_SUFFIX = ".tmp";
    private static final String DEFAULT_FILE_MODE = "rw-r--r--";

    private FileStore() {
    }

    @FunctionalInterface
    private interface TempFileWriter {
        void write(Path tempFile) throws IOException;
    }

    public static boolean exists(Path path) {
        return Files.exists(path);
    }

    /** True for a regular file; a symbolic link is <em>not</em> a regular file. */
    public static boolean isRegularFile(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    public static boolean isDirectory(Path path) {
        return Files.isDirectory(path);
    }

    public static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw ioError("stat", file, e);
        }
    }

    public static long lastModifiedMillis(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            throw ioError("stat", file, e);
        }
    }

    public static byte[] readBytes(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw ioError("read", file, e);
        }
    }

    public static String readString(Path file) {
        return new String(readBytes(file), StandardCharsets.UTF_8);
    }

    /** Opens a buffered stream; the caller must close it (use try-with-resources). */
    public static InputStream openRead(Path file) {
        try {
            return new BufferedInputStream(Files.newInputStream(file));
        } catch (IOException e) {
            throw ioError("read", file, e);
        }
    }

    public static void createDirectories(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw ioError("create directory", dir, e);
        }
    }

    public static void writeAtomic(Path destination, byte[] content) {
        writeAtomically(destination, temp -> Files.write(temp, content));
    }

    public static void writeString(Path destination, String text) {
        writeAtomic(destination, text.getBytes(StandardCharsets.UTF_8));
    }

    /** Streams {@code source} to {@code destination} without loading it into memory. */
    public static void copyAtomic(Path source, Path destination) {
        writeAtomically(destination, temp -> Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING));
    }

    /**
     * Copies {@code source} into a new temporary file inside {@code tempDir}, feeding every byte that is
     * copied to {@code digest}. The digest therefore describes exactly what was copied, even if the
     * source changes while it is being read.
     */
    public static Path copyToTemp(Path source, Path tempDir, MessageDigest digest) {
        Path temp = null;
        try {
            Files.createDirectories(tempDir);
            temp = Files.createTempFile(tempDir, TEMP_PREFIX, TEMP_SUFFIX);
            try (InputStream in = new DigestInputStream(openRead(source), digest)) {
                Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            }
            applyDefaultMode(temp);
            return temp;
        } catch (IOException e) {
            deleteQuietly(temp);
            throw ioError("copy", source, e);
        } catch (RuntimeException e) {
            deleteQuietly(temp);
            throw e;
        }
    }

    /** Moves a finished temporary file to its final location, creating parent directories. */
    public static void moveIntoPlace(Path temp, Path destination) {
        try {
            Files.createDirectories(destination.toAbsolutePath().getParent());
            move(temp, destination.toAbsolutePath());
        } catch (IOException e) {
            deleteQuietly(temp);
            throw ioError("write", destination, e);
        }
    }

    public static void appendString(Path file, String text) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw ioError("append to", file, e);
        }
    }

    /** Deletes a file if it exists, clearing a Windows read-only flag first if necessary. */
    public static void delete(Path path) {
        try {
            try {
                Files.deleteIfExists(path);
            } catch (AccessDeniedException e) {
                clearReadOnly(path);
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw ioError("delete", path, e);
        }
    }

    /** Removes empty directories above {@code file}, never touching {@code stopAt} or anything outside it. */
    public static void deleteEmptyParents(Path file, Path stopAt) {
        Path parent = file.getParent();
        while (parent != null && !parent.equals(stopAt) && parent.startsWith(stopAt)) {
            try {
                Files.delete(parent);
            } catch (DirectoryNotEmptyException e) {
                return;
            } catch (IOException e) {
                return;
            }
            parent = parent.getParent();
        }
    }

    /** Lists regular files below {@code root}, skipping any directory accepted by {@code skipDirectory}. */
    public static List<Path> listFiles(Path root, Predicate<Path> skipDirectory) {
        List<Path> files = new ArrayList<>();
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // attrs.isOther() is how Java reports a Windows junction: never descend into one.
                    boolean skip = !dir.equals(root) && (attrs.isOther() || skipDirectory.test(dir));
                    return skip ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs.isRegularFile()) {
                        files.add(file);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw ioError("list", root, e);
        }
        return files;
    }

    /**
     * The path as it is really spelled on disk (correct upper/lower case, no 8.3 short names), without
     * following symbolic links. Empty if the path does not exist.
     */
    public static Optional<Path> realPath(Path path) {
        try {
            return Optional.of(path.toRealPath(LinkOption.NOFOLLOW_LINKS));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** The directory with every symbolic link resolved; compute once and reuse with {@link #resolvesInside}. */
    public static Path resolveLinks(Path directory) {
        try {
            return directory.toRealPath();
        } catch (IOException e) {
            throw ioError("resolve", directory, e);
        }
    }

    /**
     * True if {@code candidate} (or, when it does not exist yet, its nearest existing ancestor) really
     * lives inside {@code realRoot} (already link-resolved) after resolving every symbolic link on the way.
     */
    public static boolean resolvesInside(Path candidate, Path realRoot) {
        try {
            Path existing = candidate.toAbsolutePath();
            while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
                existing = existing.getParent();
            }
            return existing != null && existing.toRealPath().startsWith(realRoot);
        } catch (IOException e) {
            return false;
        }
    }

    /** Opens (creating if needed) a file purely so that an OS-level lock can be taken on it. */
    public static FileChannel openLockChannel(Path file) {
        try {
            return FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw ioError("open lock file", file, e);
        }
    }

    private static void writeAtomically(Path destination, TempFileWriter writer) {
        Path absolute = destination.toAbsolutePath();
        Path temp = null;
        try {
            Files.createDirectories(absolute.getParent());
            temp = Files.createTempFile(absolute.getParent(), TEMP_PREFIX, TEMP_SUFFIX);
            writer.write(temp);
            applyDefaultMode(temp);
            move(temp, absolute);
            temp = null;
        } catch (IOException e) {
            throw ioError("write", destination, e);
        } finally {
            deleteQuietly(temp);
        }
    }

    private static void applyDefaultMode(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(DEFAULT_FILE_MODE));
        } catch (UnsupportedOperationException | IOException e) {
            // Non-POSIX file system (Windows): nothing to do.
        }
    }

    private static void move(Path temp, Path destination) throws IOException {
        try {
            atomicMove(temp, destination);
        } catch (AccessDeniedException e) {
            // Windows refuses to replace a read-only file; the user's file is being replaced on purpose.
            clearReadOnly(destination);
            atomicMove(temp, destination);
        }
    }

    private static void atomicMove(Path temp, Path destination) throws IOException {
        try {
            Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void clearReadOnly(Path path) {
        try {
            DosFileAttributeView view = Files.getFileAttributeView(path, DosFileAttributeView.class);
            if (view != null) {
                view.setReadOnly(false);
            }
        } catch (IOException ignored) {
            // the retry will report the real problem
        }
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort cleanup
        }
    }

    private static MiniGitException ioError(String action, Path path, IOException cause) {
        return new MiniGitException("unable to " + action + " '" + path + "': " + cause.getMessage(), cause);
    }
}
