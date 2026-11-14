package com.jujin.freeway.logging.testkit;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jujin.freeway.commons.logging.LogBootstrap;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;
import org.slf4j.LoggerFactory;

/**
 * One JVM that resolves Freeway's logging backend and reports what SLF4J actually bound. The mode
 * selects the configuration under test; the parent test forks this class so the JVM-global SLF4J
 * binding starts from scratch.
 *
 * <p>The probe also pins the JUL contract: with an external backend Freeway must leave {@code
 * java.util.logging} untouched, so JUL records neither reach the backend nor carry Freeway
 * handlers.
 */
public final class LogbackTakeoverProbe {

  public static void main(String[] args) {
    String mode = args.length > 0 ? args[0] : "takeover";
    try {
      LogBootstrap.ensureProvider();
      var factory = LoggerFactory.getILoggerFactory();
      String factoryClass = factory.getClass().getName();

      if ("default".equals(mode)) {
        require(
            "com.jujin.freeway.commons.logging.JULLoggerFactory".equals(factoryClass),
            "default must keep Freeway's JUL provider, got " + factoryClass);
        System.out.println("PROBE_OK mode=default factory=" + factoryClass);
        return;
      }

      require(
          factoryClass.startsWith("ch.qos.logback."),
          "logback must own the SLF4J binding, got " + factoryClass);

      LoggerContext context = (LoggerContext) factory;
      ListAppender<ILoggingEvent> appender = new ListAppender<>();
      appender.setContext(context);
      appender.start();
      ch.qos.logback.classic.Logger root =
          context.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
      root.setLevel(Level.INFO);
      root.addAppender(appender);

      LoggerFactory.getLogger("probe.slf4j").info("slf4j message");
      Logger.getLogger("probe.jul").info("jul message");

      List<ILoggingEvent> events = appender.list;
      long slf4jEvents =
          events.stream().filter(e -> "probe.slf4j".equals(e.getLoggerName())).count();
      long julEvents = events.stream().filter(e -> "probe.jul".equals(e.getLoggerName())).count();
      long freewayJulHandlers =
          Arrays.stream(Logger.getLogger("").getHandlers())
              .filter(h -> h.getClass().getName().startsWith("com.jujin.freeway"))
              .count();

      require(slf4jEvents == 1, "one SLF4J event expected, got " + slf4jEvents);
      require(julEvents == 0, "JUL records must NOT be forwarded into logback, got " + julEvents);
      require(
          freewayJulHandlers == 0,
          "Freeway must leave the JUL root handlers untouched, found " + freewayJulHandlers);

      System.out.println(
          "PROBE_OK mode="
              + mode
              + " factory="
              + factoryClass
              + " slf4jEvents="
              + slf4jEvents
              + " julForwarded="
              + julEvents
              + " freewayJulHandlers="
              + freewayJulHandlers);
    } catch (Throwable failure) {
      failure.printStackTrace(System.out);
      System.out.println("PROBE_FAIL " + failure.getMessage());
      System.exit(1);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) {
      throw new IllegalStateException(message);
    }
  }

  private LogbackTakeoverProbe() {}
}
