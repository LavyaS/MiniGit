package com.minigit.commands;

import com.minigit.cli.Command;
import com.minigit.cli.CommandContext;
import com.minigit.core.Config;
import com.minigit.exceptions.MiniGitException;

import java.util.List;

/** {@code minigit config <key> [<value>]} - reads or writes {@code .minigit/config}. */
public class ConfigCommand implements Command {

    private static final String LIST = "--list";

    @Override
    public String name() {
        return "config";
    }

    @Override
    public String summary() {
        return "Get or set repository configuration (user.name, user.email)";
    }

    @Override
    public String usage() {
        return "usage: minigit config <key> [<value>]\n"
                + "       minigit config --list\n\n"
                + "  minigit config user.name \"Lavya\"             set a value\n"
                + "  minigit config user.email \"lavya@example.com\"\n"
                + "  minigit config user.name                     print a value\n"
                + "Commits use user.name and user.email as their author.";
    }

    @Override
    public boolean mutatesRepository() {
        return true;
    }

    @Override
    public void execute(CommandContext context) {
        context.args().requireOnly(LIST);
        Config config = context.repository().config();
        List<String> args = context.args().positionals();

        if (context.args().hasFlag(LIST)) {
            config.all().forEach((key, value) -> context.console().println(key + "=" + value));
        } else if (args.size() == 1) {
            context.console().println(config.get(args.get(0))
                    .orElseThrow(() -> new MiniGitException("config key '" + args.get(0) + "' is not set")));
        } else if (args.size() == 2) {
            config.set(args.get(0), args.get(1));
            config.save();
        } else {
            throw new MiniGitException("usage: minigit config <key> [<value>]");
        }
    }
}
