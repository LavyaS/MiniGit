package com.minigit.cli;

import com.minigit.exceptions.MiniGitException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Splits raw arguments into positionals, value options and flags.
 * Supports {@code -m value}, {@code --message=value} and {@code --} to end option parsing.
 */
public final class CommandParser {

    private static final String END_OF_OPTIONS = "--";

    private CommandParser() {
    }

    /**
     * @param raw          arguments after the command name
     * @param valueOptions option names that consume the next argument as their value
     */
    public static ParsedArgs parse(List<String> raw, Set<String> valueOptions) {
        List<String> positionals = new ArrayList<>();
        Map<String, String> options = new LinkedHashMap<>();
        Set<String> flags = new LinkedHashSet<>();
        boolean optionsEnded = false;

        for (int i = 0; i < raw.size(); i++) {
            String token = raw.get(i);
            if (optionsEnded || !looksLikeOption(token)) {
                positionals.add(token);
            } else if (token.equals(END_OF_OPTIONS)) {
                optionsEnded = true;
            } else if (token.startsWith("--") && token.indexOf('=') > 0) {
                int eq = token.indexOf('=');
                options.put(token.substring(0, eq), token.substring(eq + 1));
            } else if (valueOptions.contains(token)) {
                if (i + 1 >= raw.size()) {
                    throw new MiniGitException("option '" + token + "' requires a value");
                }
                options.put(token, raw.get(++i));
            } else {
                flags.add(token);
            }
        }
        return new ParsedArgs(List.copyOf(positionals), options, flags);
    }

    private static boolean looksLikeOption(String token) {
        return token.length() > 1 && token.startsWith("-");
    }
}
