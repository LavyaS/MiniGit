package com.minigit.core;

/**
 * Rules for the slash-separated, repository-relative paths stored in the index and in commits.
 * Anything read from disk that breaks these rules is treated as corruption (or an attack), so a
 * hand-crafted commit can never make MiniGit write outside the working tree.
 */
public final class RepoPath {

    public static final String METADATA_DIR = ".minigit";

    private RepoPath() {
    }

    /** True for the metadata directory name, ignoring case (Windows and macOS are case-insensitive). */
    public static boolean isMetadataName(String segment) {
        return segment.equalsIgnoreCase(METADATA_DIR);
    }

    /**
     * A path is safe if it is non-empty, relative, free of backslashes and NUL characters, and has no
     * empty, {@code .}, {@code ..} or metadata-directory segments.
     */
    public static boolean isSafe(String path) {
        if (path.isEmpty() || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0) {
            return false;
        }
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..") || isMetadataName(segment)) {
                return false;
            }
        }
        return true;
    }

    /** True if any segment of the path is the metadata directory (case-insensitive). */
    public static boolean touchesMetadata(String path) {
        for (String segment : path.split("/", -1)) {
            if (isMetadataName(segment)) {
                return true;
            }
        }
        return false;
    }
}
