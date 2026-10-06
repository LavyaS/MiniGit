package com.minigit.cli;

import com.minigit.exceptions.MiniGitException;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Arguments of one command after parsing.
 *
 * @param positionals arguments that are not options, in order
 * @param options     options that carry a value, keyed by the name as typed (e.g. {@code -m})
 * @param flags       options without a value (e.g. {@code --oneline})
 */
public record ParsedArgs(List<String> positionals, Map<String, String> options, Set<String> flags) {

    public boolean hasFlag(String... names) {
        for (String name : names) {
            if (flags.contains(name)) {
                return true;
            }
        }
        return false;
    }

    public Optional<String> option(String... names) {
        for (String name : names) {
            if (options.containsKey(name)) {
                return Optional.of(options.get(name));
            }
        }
        return Optional.empty();
    }

    /** Fails if more than {@code max} non-option arguments were given, instead of silently ignoring them. */
    public void requireMaxPositionals(int max) {
        if (positionals.size() > max) {
            throw new MiniGitException("unexpected argument '" + positionals.get(max) + "' (see 'minigit help')");
        }
    }

    /** Fails with a helpful message if any option outside {@code allowed} was given. */
    public void requireOnly(String... allowed) {
        Set<String> known = Set.of(allowed);
        for (String name : flags) {
            rejectUnknown(name, known);
        }
        for (String name : options.keySet()) {
            rejectUnknown(name, known);
        }
    }

    private static void rejectUnknown(String name, Set<String> known) {
        if (!known.contains(name)) {
            throw new MiniGitException("unknown option '" + name + "'");
        }
    }
}
