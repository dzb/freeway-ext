package com.jujin.freeway.bench.cli;

import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.Container;
import com.jujin.freeway.ioc.ModuleEx;
import com.jujin.freeway.ioc.RuntimeHook;

/**
 * Registers all CLI command implementations as contributions.
 * New commands are added to {@link #bind(Binder)}.
 */
public final class CliModule implements ModuleEx {

    private static volatile Container container;

    @Override
    public void bind(Binder binder) {
        var cmds = binder.contribute(Command.class);
        cmds.add(new RunCommand());
        cmds.add(new ListCommand());
        cmds.add(new CompareCommand());
        cmds.add(new HistoryCommand());
        cmds.add(new SuiteCommand());
        // AppRuntime no longer exposes the container; capture it at startup
        // so the CLI dispatcher can reach extension points.
        binder.contribute(RuntimeHook.class)
            .add(c -> CliModule.container = c);

        // Core's DbModule orders schema/migration hooks before
        // "freeway.http.server". This CLI has no HTTP server, so register the
        // ordering anchor as a no-op while auto-discovery is disabled; without
        // it the core fails fast on the unresolved ordering reference.
        // TODO: prefer an optional ordering reference in core over this anchor.
        binder.contribute(RuntimeHook.class)
            .add("freeway.http.server", c -> {});
    }

    /** Container captured by the runtime hook during {@code FreewayApp.run(...)}. */
    public static Container container() {
        return container;
    }

    /**
     * Dispatches the first CLI argument to the matching Command.
     * Called by {@link BenchApp} after the container starts.
     *
     * @return {@code true} when a command ran, {@code false} when the command
     *         name was unknown (caller should exit non-zero)
     */
    public static boolean dispatch(Container container, String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Usage: bench <command> [--key=value ...]");
            System.out.println("Commands:");
            System.out.println("  run      Run a benchmark");
            System.out.println("  suite    Run multiple engine/scenario/concurrency combinations");
            System.out.println("  list     Show recent benchmark runs");
            System.out.println("  compare  Compare two benchmark runs");
            System.out.println("  history  Show performance trend over time");
            return true;
        }

        String commandName = args[0];
        var cmdArgs = java.util.Map.<String, String>of();
        if (args.length > 1) {
            var map = new java.util.LinkedHashMap<String, String>();
            for (int i = 1; i < args.length; i++) {
                String a = args[i];
                if (a.startsWith("--")) {
                    int eq = a.indexOf('=');
                    if (eq > 0) {
                        map.put(a.substring(2, eq), a.substring(eq + 1));
                    } else {
                        map.put(a.substring(2), "true");
                    }
                }
            }
            cmdArgs = java.util.Map.copyOf(map);
        }

        var ctx = new Command.Context(container, commandName, cmdArgs);

        var commands = container.extension(Command.class).all();
        for (var cmd : commands) {
            if (cmd.getClass().getSimpleName()
                    .equalsIgnoreCase(commandName + "Command")) {
                cmd.run(ctx);
                return true;
            }
        }

        System.err.println("Unknown command: " + commandName);
        return false;
    }
}
