package com.minigit.core;

import com.minigit.exceptions.MiniGitException;
import com.minigit.hash.HashUtils;
import com.minigit.storage.FileStore;
import com.minigit.utils.TimeUtils;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The staging area. It holds the snapshot the next commit will record, as sorted
 * {@code path -> IndexEntry} pairs persisted one per line:
 *
 * <pre>&lt;blob hash&gt; &lt;size&gt; &lt;mtime millis&gt; &lt;path&gt;</pre>
 *
 * Right after a commit (or checkout) the index equals the HEAD snapshot, i.e. nothing is staged.
 */
public class Index {

    /** Entries whose file changed within this window of being saved cannot be trusted by size/mtime. */
    private static final long RACY_WINDOW_MILLIS = 2000;
    private static final int FIELDS_PER_LINE = 4;

    private final Path file;
    private final TreeMap<String, IndexEntry> entries = new TreeMap<>();

    private Index(Path file) {
        this.file = file;
    }

    /**
     * Loads the index. {@code init} always creates the file, so a missing one means the repository is
     * damaged; treating it as "nothing staged" would make the next commit silently delete every file.
     */
    public static Index load(Path file) {
        Index index = new Index(file);
        if (!FileStore.exists(file)) {
            throw new MiniGitException("the index file is missing (" + file + "); the repository is damaged. "
                    + "Restore it from a backup, or recreate it with an empty file to start from nothing staged.");
        }
        for (String line : FileStore.readString(file).split("\n")) {
            if (!line.isEmpty()) {
                index.parseLine(line);
            }
        }
        return index;
    }

    private void parseLine(String line) {
        String[] fields = line.split(" ", FIELDS_PER_LINE);
        if (fields.length != FIELDS_PER_LINE || !HashUtils.isValidHash(fields[0]) || !RepoPath.isSafe(fields[3])) {
            throw new MiniGitException("index file is corrupt: '" + line + "'");
        }
        try {
            entries.put(fields[3], new IndexEntry(fields[0], Long.parseLong(fields[1]), Long.parseLong(fields[2])));
        } catch (NumberFormatException e) {
            throw new MiniGitException("index file is corrupt: '" + line + "'", e);
        }
    }

    public void save() {
        long now = TimeUtils.nowMillis();
        StringBuilder sb = new StringBuilder();
        entries.forEach((path, entry) -> {
            IndexEntry stored = entry.mtimeMillis() > now - RACY_WINDOW_MILLIS ? entry.smudged() : entry;
            sb.append(stored.hash()).append(' ').append(stored.size()).append(' ')
                    .append(stored.mtimeMillis()).append(' ').append(path).append('\n');
        });
        FileStore.writeString(file, sb.toString());
    }

    public Optional<IndexEntry> get(String path) {
        return Optional.ofNullable(entries.get(path));
    }

    public boolean contains(String path) {
        return entries.containsKey(path);
    }

    public void put(String path, IndexEntry entry) {
        entries.put(path, entry);
    }

    public void remove(String path) {
        entries.remove(path);
    }

    /** Removes every entry below {@code directory} (for example {@code a/b} and {@code a/b/c.txt} for {@code a}). */
    public void removeUnder(String directory) {
        String prefix = directory + "/";
        entries.subMap(prefix, prefix + Character.MAX_VALUE).clear();
    }

    public Set<String> paths() {
        return Collections.unmodifiableSet(entries.keySet());
    }

    public Map<String, IndexEntry> entries() {
        return Collections.unmodifiableMap(entries);
    }

    /** The staged snapshot as {@code path -> blob hash}. */
    public Map<String, String> tree() {
        Map<String, String> tree = new TreeMap<>();
        entries.forEach((path, entry) -> tree.put(path, entry.hash()));
        return tree;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
