package com.minigit.diff;

import com.minigit.diff.DiffResult.DiffLine;
import com.minigit.diff.DiffResult.LineType;
import com.minigit.utils.FileUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Line-based diff built on the longest common subsequence (LCS) of the two files.
 * Identical leading and trailing lines are trimmed first, which keeps the quadratic LCS table
 * small for typical edits. Extremely large changes fall back to "remove everything, add everything".
 */
public class DiffEngine {

    /** Upper bound for the LCS table (cells); beyond this the diff degrades gracefully. */
    private static final long MAX_TABLE_CELLS = 10_000_000L;

    public DiffResult diff(byte[] oldContent, byte[] newContent) {
        boolean identical = Arrays.equals(oldContent, newContent);
        if (identical) {
            return DiffResult.unchanged();
        }
        if (FileUtils.isBinary(oldContent) || FileUtils.isBinary(newContent)) {
            return DiffResult.binaryDiff();
        }
        DiffResult result = diff(FileUtils.splitLines(new String(oldContent, StandardCharsets.UTF_8)),
                FileUtils.splitLines(new String(newContent, StandardCharsets.UTF_8)));
        // Same lines but different bytes (CRLF vs LF, missing final newline, ...): never report "no change".
        return result.hasChanges() ? result : DiffResult.onlyInvisibleChanges();
    }

    public DiffResult diff(List<String> oldLines, List<String> newLines) {
        int prefix = commonPrefix(oldLines, newLines);
        int suffix = commonSuffix(oldLines, newLines, prefix);
        List<String> oldMiddle = oldLines.subList(prefix, oldLines.size() - suffix);
        List<String> newMiddle = newLines.subList(prefix, newLines.size() - suffix);

        List<DiffLine> result = new ArrayList<>();
        addAll(result, LineType.CONTEXT, oldLines.subList(0, prefix));
        result.addAll(diffMiddle(oldMiddle, newMiddle));
        addAll(result, LineType.CONTEXT, oldLines.subList(oldLines.size() - suffix, oldLines.size()));
        return new DiffResult(false, result, false);
    }

    private List<DiffLine> diffMiddle(List<String> oldLines, List<String> newLines) {
        List<DiffLine> result = new ArrayList<>();
        if ((long) oldLines.size() * newLines.size() > MAX_TABLE_CELLS) {
            addAll(result, LineType.REMOVED, oldLines);
            addAll(result, LineType.ADDED, newLines);
            return result;
        }
        int[][] lcs = lcsTable(oldLines, newLines);
        int i = 0;
        int j = 0;
        while (i < oldLines.size() && j < newLines.size()) {
            if (oldLines.get(i).equals(newLines.get(j))) {
                result.add(new DiffLine(LineType.CONTEXT, oldLines.get(i)));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                result.add(new DiffLine(LineType.REMOVED, oldLines.get(i++)));
            } else {
                result.add(new DiffLine(LineType.ADDED, newLines.get(j++)));
            }
        }
        addAll(result, LineType.REMOVED, oldLines.subList(i, oldLines.size()));
        addAll(result, LineType.ADDED, newLines.subList(j, newLines.size()));
        return result;
    }

    /** {@code table[i][j]} = LCS length of {@code old[i..]} and {@code new[j..]}. */
    private int[][] lcsTable(List<String> oldLines, List<String> newLines) {
        int[][] table = new int[oldLines.size() + 1][newLines.size() + 1];
        for (int i = oldLines.size() - 1; i >= 0; i--) {
            for (int j = newLines.size() - 1; j >= 0; j--) {
                table[i][j] = oldLines.get(i).equals(newLines.get(j))
                        ? table[i + 1][j + 1] + 1
                        : Math.max(table[i + 1][j], table[i][j + 1]);
            }
        }
        return table;
    }

    private static int commonPrefix(List<String> a, List<String> b) {
        int max = Math.min(a.size(), b.size());
        int n = 0;
        while (n < max && a.get(n).equals(b.get(n))) {
            n++;
        }
        return n;
    }

    private static int commonSuffix(List<String> a, List<String> b, int prefix) {
        int max = Math.min(a.size(), b.size()) - prefix;
        int n = 0;
        while (n < max && a.get(a.size() - 1 - n).equals(b.get(b.size() - 1 - n))) {
            n++;
        }
        return n;
    }

    private static void addAll(List<DiffLine> target, LineType type, List<String> lines) {
        for (String line : lines) {
            target.add(new DiffLine(type, line));
        }
    }
}
