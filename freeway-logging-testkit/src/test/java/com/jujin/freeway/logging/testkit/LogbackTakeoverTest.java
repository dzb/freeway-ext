package com.jujin.freeway.logging.testkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Cross-JVM proof that a real third-party SLF4J backend takes over when selected, that JUL is left
 * untouched in that mode, and that Freeway's own provider stays the default while logback sits on
 * the classpath.
 *
 * <p>Same-JVM assertions cannot catch a binding misconfiguration: SLF4J binds once per JVM, and
 * this test JVM already owns the Freeway provider. Forking starts the selection from a clean slate,
 * which is the defect this file exists to prevent.
 */
class LogbackTakeoverTest {

  private static final String JUL_FACTORY = "com.jujin.freeway.commons.logging.JULLoggerFactory";

  @Test
  void explicitLogbackTakesOverWithoutTouchingJul(@TempDir Path work) throws Exception {
    ProbeRun run = run(work, "takeover", "-Dfreeway.log.system=logback");

    assertEquals(0, run.exitCode(), run.output());
    assertTrue(run.output().contains("PROBE_OK mode=takeover"), run.output());
    assertTrue(run.output().contains("factory=ch.qos.logback.classic.LoggerContext"), run.output());
    assertTrue(
        run.output().contains("[Freeway] logging: logback"),
        "the startup report must name logback: " + run.output());
    assertTrue(
        run.output().contains("julForwarded=0"), "JUL must not be forwarded: " + run.output());
    assertTrue(
        run.output().contains("freewayJulHandlers=0"),
        "Freeway must not touch the JUL root handlers: " + run.output());
  }

  @Test
  void autoOptsIntoLogback(@TempDir Path work) throws Exception {
    ProbeRun run = run(work, "auto", "-Dfreeway.log.system=auto");

    assertEquals(0, run.exitCode(), run.output());
    assertTrue(run.output().contains("PROBE_OK mode=auto"), run.output());
    assertTrue(run.output().contains("factory=ch.qos.logback.classic.LoggerContext"), run.output());
  }

  @Test
  void freewayLoggingRemainsTheDefaultWithLogbackOnTheClasspath(@TempDir Path work)
      throws Exception {
    ProbeRun run = run(work, "default");

    assertEquals(0, run.exitCode(), run.output());
    assertTrue(run.output().contains("PROBE_OK mode=default"), run.output());
    assertTrue(run.output().contains("factory=" + JUL_FACTORY), run.output());
    assertTrue(run.output().contains("[Freeway] logging: freeway-logging (JUL)"), run.output());
    assertTrue(
        run.output().contains("logback is on the classpath but not selected"),
        "the default must report the backend it ignored: " + run.output());
    assertFalse(run.output().contains("PROBE_FAIL"), run.output());
  }

  private static ProbeRun run(Path work, String mode, String... jvmArgs) throws Exception {
    List<String> command = new ArrayList<>();
    command.add(ProcessHandle.current().info().command().orElse("java"));
    command.addAll(List.of(jvmArgs));
    command.add("-cp");
    // surefire's forked JVM exposes the real classpath here; java.class.path
    // may be the booter jar.
    command.add(
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
    command.add(LogbackTakeoverProbe.class.getName());
    command.add(mode);

    Process process =
        new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true).start();
    String output = readAll(process);
    if (!process.waitFor(60, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new AssertionError("probe did not finish: " + output);
    }
    return new ProbeRun(process.exitValue(), output);
  }

  private static String readAll(Process process) {
    StringBuilder out = new StringBuilder();
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        out.append(line).append('\n');
      }
    } catch (Exception e) {
      out.append("(unreadable: ").append(e).append(')');
    }
    return out.toString();
  }

  private record ProbeRun(int exitCode, String output) {}
}
