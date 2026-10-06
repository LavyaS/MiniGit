package com.minigit.hash;

import com.minigit.exceptions.MiniGitException;
import com.minigit.storage.FileStore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** SHA-256 helpers. A hash is always 64 lowercase hexadecimal characters. */
public final class HashUtils {

    public static final String ALGORITHM = "SHA-256";
    public static final int HASH_LENGTH = 64;
    public static final int SHORT_HASH_LENGTH = 7;

    private static final int BUFFER_SIZE = 8192;
    private static final Pattern VALID_HASH = Pattern.compile("[0-9a-f]{" + HASH_LENGTH + "}");

    private HashUtils() {
    }

    public static String sha256(byte[] content) {
        return toHex(newDigest().digest(content));
    }

    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Hashes a file in fixed-size chunks so large files are never fully loaded. */
    public static String sha256(Path file) {
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[BUFFER_SIZE];
        try (InputStream in = FileStore.openRead(file)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new MiniGitException("unable to read '" + file + "': " + e.getMessage(), e);
        }
        return toHex(digest.digest());
    }

    public static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(ALGORITHM + " is required by the Java platform", e);
        }
    }

    public static String toHex(byte[] digest) {
        return HexFormat.of().formatHex(digest);
    }

    public static boolean isValidHash(String candidate) {
        return candidate != null && VALID_HASH.matcher(candidate).matches();
    }

    public static String shortHash(String hash) {
        return hash.substring(0, Math.min(SHORT_HASH_LENGTH, hash.length()));
    }
}
