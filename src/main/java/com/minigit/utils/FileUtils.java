package com.minigit.utils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Small, side-effect-free helpers for paths and text content. */
public final class FileUtils {

    private static final int BINARY_SNIFF_LENGTH = 8000;

    private FileUtils() {
    }

    /** Converts a relative path to the slash-separated form stored in the index and in commits. */
    public static String toRepoPath(Path relative) {
        StringBuilder sb = new StringBuilder();
        for (Path part : relative) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(part);
        }
        return sb.toString();
    }

    /** Git's heuristic: content containing a NUL byte near the start is binary. */
    public static boolean isBinary(byte[] content) {
        int limit = Math.min(content.length, BINARY_SNIFF_LENGTH);
        for (int i = 0; i < limit; i++) {
            if (content[i] == 0) {
                return true;
            }
        }
        return false;
    }

    /** Splits text into lines, accepting both LF and CRLF; no trailing empty line. */
    public static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines.add(stripCarriageReturn(text.substring(start, i)));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            lines.add(stripCarriageReturn(text.substring(start)));
        }
        return lines;
    }

    private static String stripCarriageReturn(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }
}
