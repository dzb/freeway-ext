/*
 * Copyright 2026 dzb
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.jujin.freeway.bench.cli;

import com.jujin.freeway.ioc.Container;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * A CLI command contributed to the bench application. Implementations are registered via {@code
 * binder.contribute(Command.class)}.
 */
public interface Command {

  /**
   * The name this command answers to on the command line (for example {@code "run"}). Dispatch
   * matches on it, so renaming or repackaging the implementation cannot silently unhook it.
   */
  String name();

  /** Execute this command. */
  void run(Context ctx) throws Exception;

  /**
   * Runtime context injected by the CLI dispatcher. Carries the process exit code so a command can
   * fail a gate: {@code 0} is success, and the highest code any command records wins.
   */
  final class Context {
    private final Container container;
    private final String command;
    private final Map<String, String> args;

    /**
     * The option keys this command asked for — what the dispatcher's unknown-option check exempts.
     */
    private final Set<String> consumed = new LinkedHashSet<>();

    private int exitCode;

    public Context(Container container, String command, Map<String, String> args) {
      this.container = container;
      this.command = command;
      this.args = Map.copyOf(args);
    }

    public Container container() {
      return container;
    }

    public String command() {
      return command;
    }

    /**
     * The raw argument map, for display only. Reading an option through {@link #get}, {@link
     * #getInt}, {@link #parse} or {@link #has} is what marks it consumed; this view deliberately
     * does not, so {@link #unconsumed()} can still name a mistyped flag a command printed past.
     */
    public Map<String, String> args() {
      return args;
    }

    /** Returns the value for a key, or the default if absent. */
    public String get(String key, String defaultValue) {
      consumed.add(key);
      return args.getOrDefault(key, defaultValue);
    }

    /** Returns an int value for a key, or the default if absent. */
    public int getInt(String key, int defaultValue) {
      consumed.add(key);
      String v = args.get(key);
      if (v == null) return defaultValue;
      try {
        return Integer.parseInt(v.trim());
      } catch (NumberFormatException e) {
        throw new UsageException("--" + key + " must be an integer, got '" + v + "'");
      }
    }

    /** Whether the option was given. Marks it consumed, like the typed readers. */
    public boolean has(String key) {
      consumed.add(key);
      return args.containsKey(key);
    }

    /**
     * Parses a flag through {@code parser}, or returns {@code defaultValue} when the flag is
     * absent. A parser failure is reported as a usage error naming the flag, never as a stack
     * trace.
     */
    public <T> T parse(String key, Function<String, T> parser, T defaultValue) {
      consumed.add(key);
      String v = args.get(key);
      if (v == null) return defaultValue;
      try {
        return parser.apply(v);
      } catch (RuntimeException e) {
        throw new UsageException(
            "--"
                + key
                + " is invalid: '"
                + v
                + "'"
                + (e.getMessage() == null ? "" : " — " + e.getMessage()));
      }
    }

    /**
     * The options no command read. The dispatcher reports them: a mistyped flag silently falling
     * back to its default measures something other than what the caller asked for.
     */
    public Set<String> unconsumed() {
      var rest = new LinkedHashSet<>(args.keySet());
      rest.removeAll(consumed);
      return Set.copyOf(rest);
    }

    /** Records a non-zero exit code for the process (usage error, failed gate). */
    public void exitCode(int code) {
      this.exitCode = Math.max(this.exitCode, code);
    }

    /** The process exit code this command asks for; {@code 0} when it never set one. */
    public int exitCode() {
      return exitCode;
    }
  }
}
