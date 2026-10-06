package com.minigit.core;

import com.minigit.exceptions.MiniGitException;
import com.minigit.storage.FileStore;

import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Repository configuration stored as {@code key=value} lines in {@code .minigit/config}. */
public class Config {

    public static final String USER_NAME = "user.name";
    public static final String USER_EMAIL = "user.email";

    private static final String HEADER = "# MiniGit configuration\n";
    private static final Pattern VALID_KEY = Pattern.compile("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)+");

    private final Path file;
    private final Map<String, String> values = new TreeMap<>();

    private Config(Path file) {
        this.file = file;
    }

    public static Config load(Path file) {
        Config config = new Config(file);
        if (FileStore.exists(file)) {
            for (String line : FileStore.readString(file).split("\n")) {
                int eq = line.indexOf('=');
                if (!line.startsWith("#") && eq > 0) {
                    config.values.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
                }
            }
        }
        return config;
    }

    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get(key));
    }

    public void set(String key, String value) {
        if (!VALID_KEY.matcher(key).matches()) {
            throw new MiniGitException("invalid config key '" + key + "' (expected something like 'user.name')");
        }
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new MiniGitException("config values cannot contain line breaks");
        }
        values.put(key, value.strip());
    }

    public Map<String, String> all() {
        return Collections.unmodifiableMap(values);
    }

    public void save() {
        StringBuilder sb = new StringBuilder(HEADER);
        values.forEach((key, value) -> sb.append(key).append('=').append(value).append('\n'));
        FileStore.writeString(file, sb.toString());
    }

    /** The {@code Name <email>} author string, or an explanatory error if identity is not configured. */
    public String requireAuthor() {
        Optional<String> name = get(USER_NAME).filter(v -> !v.isBlank());
        Optional<String> email = get(USER_EMAIL).filter(v -> !v.isBlank());
        if (name.isEmpty() || email.isEmpty()) {
            throw new MiniGitException("author identity unknown\n\n"
                    + "Tell MiniGit who you are by running:\n\n"
                    + "  minigit config user.name \"Your Name\"\n"
                    + "  minigit config user.email \"you@example.com\"");
        }
        return name.get() + " <" + email.get() + ">";
    }
}
