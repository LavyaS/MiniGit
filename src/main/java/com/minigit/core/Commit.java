package com.minigit.core;

import com.minigit.exceptions.MiniGitException;
import com.minigit.hash.HashUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * An immutable commit: a full snapshot ({@code path -> blob hash}), a parent pointer and metadata.
 * The commit hash is the SHA-256 of its serialized form, so any change produces a new identity.
 *
 * <pre>
 * parent &lt;hash&gt;            (absent for the first commit)
 * author Name &lt;email&gt;
 * timestamp &lt;epoch millis&gt;
 * tree &lt;entry count&gt;
 * &lt;blob hash&gt; &lt;path&gt;       (one line per file)
 *                          (blank line)
 * &lt;message&gt;
 * </pre>
 *
 * @param hash      the commit id
 * @param parent    hash of the parent commit, or {@code null} for a root commit
 * @param author    {@code Name <email>}
 * @param timestamp creation time in epoch milliseconds
 * @param message   commit message
 * @param tree      snapshot of tracked files, sorted by path
 */
public record Commit(String hash, String parent, String author, long timestamp, String message,
                     Map<String, String> tree) {

    private static final String PARENT = "parent ";
    private static final String AUTHOR = "author ";
    private static final String TIMESTAMP = "timestamp ";
    private static final String TREE = "tree ";

    public Commit {
        tree = Collections.unmodifiableMap(new TreeMap<>(tree));
    }

    /** Builds a commit and computes its hash from the serialized content. */
    public static Commit create(String parent, String author, long timestamp, String message,
                                Map<String, String> tree) {
        Commit unhashed = new Commit("", parent, author, timestamp, message, tree);
        return new Commit(HashUtils.sha256(unhashed.serialize()), parent, author, timestamp, message, tree);
    }

    public boolean hasParent() {
        return parent != null;
    }

    public String shortHash() {
        return HashUtils.shortHash(hash);
    }

    /** First line of the message. */
    public String subject() {
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }

    /** The exact text that is hashed and stored in the object store. */
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        if (hasParent()) {
            sb.append(PARENT).append(parent).append('\n');
        }
        sb.append(AUTHOR).append(author).append('\n');
        sb.append(TIMESTAMP).append(timestamp).append('\n');
        sb.append(TREE).append(tree.size()).append('\n');
        tree.forEach((path, blob) -> sb.append(blob).append(' ').append(path).append('\n'));
        sb.append('\n').append(message);
        return sb.toString();
    }

    public static Commit parse(String hash, String text) {
        String[] lines = text.split("\n", -1);
        String parent = null;
        String author = null;
        Long timestamp = null;
        Map<String, String> tree = new TreeMap<>();
        int i = 0;
        while (i < lines.length && !lines[i].isEmpty()) {
            String line = lines[i++];
            if (line.startsWith(PARENT)) {
                parent = line.substring(PARENT.length());
            } else if (line.startsWith(AUTHOR)) {
                author = line.substring(AUTHOR.length());
            } else if (line.startsWith(TIMESTAMP)) {
                timestamp = parseLong(line.substring(TIMESTAMP.length()), hash);
            } else if (line.startsWith(TREE)) {
                long count = parseLong(line.substring(TREE.length()), hash);
                if (count < 0 || count > lines.length - i) {
                    throw corrupt(hash);
                }
                for (long n = 0; n < count; n++) {
                    parseTreeEntry(lines[i++], tree, hash);
                }
            } else {
                throw corrupt(hash);
            }
        }
        if (author == null || timestamp == null || i >= lines.length) {
            throw corrupt(hash);
        }
        String message = String.join("\n", Arrays.copyOfRange(lines, i + 1, lines.length));
        return new Commit(hash, parent, author, timestamp, message, tree);
    }

    private static void parseTreeEntry(String line, Map<String, String> tree, String commitHash) {
        int split = HashUtils.HASH_LENGTH;
        if (line.length() <= split + 1 || line.charAt(split) != ' ') {
            throw corrupt(commitHash);
        }
        String blob = line.substring(0, split);
        String path = line.substring(split + 1);
        if (!HashUtils.isValidHash(blob) || !RepoPath.isSafe(path)) {
            throw corrupt(commitHash);
        }
        tree.put(path, blob);
    }

    private static long parseLong(String value, String commitHash) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw corrupt(commitHash);
        }
    }

    private static MiniGitException corrupt(String hash) {
        return new MiniGitException("commit " + hash + " is corrupt");
    }
}
