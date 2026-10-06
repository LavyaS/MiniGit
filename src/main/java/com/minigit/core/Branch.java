package com.minigit.core;

import com.minigit.exceptions.MiniGitException;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A branch is nothing more than a name pointing at a commit; no files are copied.
 *
 * @param name       branch name
 * @param commitHash commit it points to, or {@code null} if the branch has no commits yet
 * @param current    whether HEAD currently points at this branch
 */
public record Branch(String name, String commitHash, boolean current) {

    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Pattern WINDOWS_RESERVED = Pattern.compile("(con|prn|aux|nul|com[1-9]|lpt[1-9])");

    /** Rejects names that would be unsafe, ambiguous or unusable on Windows as a file in {@code refs/heads}. */
    public static void validateName(String name) {
        if (!isValidName(name)) {
            throw new MiniGitException("'" + name + "' is not a valid branch name (letters, digits, '.', '_' and '-' "
                    + "only, starting with a letter or digit, and not a reserved name such as 'con' or 'aux')");
        }
    }

    public static boolean isValidName(String name) {
        if (name == null || !VALID_NAME.matcher(name).matches() || name.contains("..")
                || name.endsWith(".lock") || name.endsWith(".")) {
            return false;
        }
        String stem = name.contains(".") ? name.substring(0, name.indexOf('.')) : name;
        return !WINDOWS_RESERVED.matcher(stem.toLowerCase(Locale.ROOT)).matches();
    }
}
