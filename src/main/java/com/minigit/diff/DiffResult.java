package com.minigit.diff;

import java.util.ArrayList;
import java.util.List;

/**
 * Outcome of comparing two versions of a file: either "binary", an ordered list of lines each
 * marked as unchanged, added or removed, or "invisible only" when the lines are identical but the
 * bytes are not (different line endings, trailing newline or character encoding).
 */
public record DiffResult(boolean binary, List<DiffLine> lines, boolean invisibleOnly) {

    private static final int CONTEXT_LINES = 3;
    static final String INVISIBLE_ONLY_NOTE =
            "Files differ only in line endings, trailing newline or character encoding";

    public enum LineType {
        CONTEXT(' '), ADDED('+'), REMOVED('-');

        private final char prefix;

        LineType(char prefix) {
            this.prefix = prefix;
        }

        public char prefix() {
            return prefix;
        }
    }

    public record DiffLine(LineType type, String text) {
    }

    public static DiffResult binaryDiff() {
        return new DiffResult(true, List.of(), false);
    }

    public static DiffResult unchanged() {
        return new DiffResult(false, List.of(), false);
    }

    public static DiffResult onlyInvisibleChanges() {
        return new DiffResult(false, List.of(), true);
    }

    public long additions() {
        return lines.stream().filter(l -> l.type() == LineType.ADDED).count();
    }

    public long deletions() {
        return lines.stream().filter(l -> l.type() == LineType.REMOVED).count();
    }

    /** True for binary or invisible-only differences, or at least one added/removed text line. */
    public boolean hasChanges() {
        return binary || invisibleOnly || additions() > 0 || deletions() > 0;
    }

    /** Renders a unified diff with three lines of context. Returns an empty string if nothing changed. */
    public String format(String oldName, String newName) {
        if (!hasChanges()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("--- ").append(oldName).append('\n');
        sb.append("+++ ").append(newName).append('\n');
        if (binary) {
            sb.append("Binary files differ\n");
            return sb.toString();
        }
        if (invisibleOnly) {
            sb.append(INVISIBLE_ONLY_NOTE).append('\n');
            return sb.toString();
        }
        for (int[] range : hunkRanges()) {
            appendHunk(sb, range[0], range[1]);
        }
        return sb.toString();
    }

    /** Index ranges {@code [start, end)} covering every change plus its context, merged when they touch. */
    private List<int[]> hunkRanges() {
        List<int[]> ranges = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).type() == LineType.CONTEXT) {
                continue;
            }
            int start = Math.max(0, i - CONTEXT_LINES);
            int end = Math.min(lines.size(), i + CONTEXT_LINES + 1);
            if (!ranges.isEmpty() && start <= ranges.get(ranges.size() - 1)[1]) {
                ranges.get(ranges.size() - 1)[1] = end;
            } else {
                ranges.add(new int[]{start, end});
            }
        }
        return ranges;
    }

    private void appendHunk(StringBuilder sb, int start, int end) {
        int oldBefore = 0;
        int newBefore = 0;
        for (int i = 0; i < start; i++) {
            LineType type = lines.get(i).type();
            if (type != LineType.ADDED) {
                oldBefore++;
            }
            if (type != LineType.REMOVED) {
                newBefore++;
            }
        }
        int oldCount = 0;
        int newCount = 0;
        StringBuilder body = new StringBuilder();
        for (int i = start; i < end; i++) {
            DiffLine line = lines.get(i);
            if (line.type() != LineType.ADDED) {
                oldCount++;
            }
            if (line.type() != LineType.REMOVED) {
                newCount++;
            }
            body.append(line.type().prefix()).append(line.text()).append('\n');
        }
        sb.append("@@ -").append(oldCount == 0 ? oldBefore : oldBefore + 1).append(',').append(oldCount)
                .append(" +").append(newCount == 0 ? newBefore : newBefore + 1).append(',').append(newCount)
                .append(" @@\n").append(body);
    }
}
