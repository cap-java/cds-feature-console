package com.sap.cds.feature.console.info.collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sap.cds.feature.console.service.CommandEvent;
import com.sap.cds.feature.console.service.InfoEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

class LogCollectorTest extends InfoCollectorTestBase {

  private static final Logger LOG = LoggerFactory.getLogger(LogCollectorTest.class);

  @AfterEach
  void resetLogEventsStore() {
    remoteMonitoringTestHandler.resetInfoEventsStore();
    updateLogLevel(null, LOG.getName());
    remoteMonitoringTestHandler.resetInfoEventsStore();
    List<InfoEvent> logs = remoteMonitoringTestHandler.getLogEvents();
    assertEquals(0, logs.size());
  }

  static Stream<Arguments> logLevelFilterData() {
    return Stream.of(
        Arguments.of(Level.INFO, 1, 0),
        Arguments.of(Level.ERROR, 0, 0));
  }

  @ParameterizedTest(name = "level={0}")
  @MethodSource("logLevelFilterData")
  void testUpdateLogLevelsFiltersLowerPriority(Level level, int expectedInfo, int expectedDebug) {
    updateLogLevel(level, LOG.getName());
    LOG.info("info log");
    LOG.debug("debug log");

    assertEquals(expectedInfo, remoteMonitoringTestHandler.getLogEventsByLevel(Level.INFO).size());
    assertEquals(expectedDebug, remoteMonitoringTestHandler.getLogEventsByLevel(Level.DEBUG).size());
  }

  @Test
  void testUpdateLogLevelsToDebug() {
    LOG.debug("debug log");
    assertEquals(0, remoteMonitoringTestHandler.getLogEventsByLevel(Level.DEBUG).size());

    updateLogLevel(Level.DEBUG, LOG.getName());
    LOG.debug("debug log");

    assertEquals(0, remoteMonitoringTestHandler.getLogEventsByLevel(Level.INFO).size());
    assertEquals(1, remoteMonitoringTestHandler.getLogEventsByLevel(Level.DEBUG).size());
  }

  @Test
  void testUpdateRootLogLevelsToWarnAndRestore() {
    updateLogLevel(Level.WARN, "root");
    LOG.info("info log");
    LOG.debug("debug log");
    System.out.println("out");

    assertEquals(0, remoteMonitoringTestHandler.getLogEventsByLevel(Level.INFO).size());
    assertEquals(0, remoteMonitoringTestHandler.getLogEventsByLevel(Level.DEBUG).size());

    updateLogLevel(Level.INFO, "root");
    LOG.info("info log");
    LOG.debug("debug log");

    assertEquals(1, remoteMonitoringTestHandler.getLogEventsByLevel(Level.INFO).size());
    assertEquals(0, remoteMonitoringTestHandler.getLogEventsByLevel(Level.DEBUG).size());
  }

  static Stream<Arguments> loggerGroupData() {
    return Stream.of(
        Arguments.of("handlers", true, Level.WARN,
            "com.sap.cds.services.impl.ServiceImpl", Level.INFO, 1),
        Arguments.of("com.sap.cds.feature.console", false, Level.DEBUG,
            null, Level.DEBUG, 0));
  }

  @ParameterizedTest(name = "logger={0}, group={1}")
  @MethodSource("loggerGroupData")
  void testUpdateLogLevelsForLoggerGroup(
      String loggerName, boolean isGroup, Level level,
      String testLoggerName, Level resetLevel, int resetExpected) {
    Map<String, Object> loggerConfig = new HashMap<>();
    loggerConfig.put("logger", loggerName);
    loggerConfig.put("level", level.toString());
    loggerConfig.put("group", isGroup);

    List<Map<String, Object>> loggers = new ArrayList<>();
    loggers.add(loggerConfig);
    Map<String, Object> data = new HashMap<>();
    data.put("loggers", loggers);

    CommandEvent update = new CommandEvent(LogCollector.COMMAND_UPDATE, data);
    remoteMonitoringService.emit(update);

    Logger testLogger = testLoggerName != null
        ? LoggerFactory.getLogger(testLoggerName) : LOG;
    logAtLevel(testLogger, level, "test log for group");
    assertEquals(1,
        remoteMonitoringTestHandler.getLogEventsByMessage("test log for group").size());

    remoteMonitoringTestHandler.resetInfoEventsStore();
    loggerConfig.put("level", null);
    remoteMonitoringService.emit(update);
    logAtLevel(testLogger, resetLevel, "test log after reset");

    assertEquals(resetExpected,
        remoteMonitoringTestHandler.getLogEventsByMessage("test log after reset").size());
  }

  private void updateLogLevel(Level level, String loggerName) {
    Map<String, Object> logger = new HashMap<>();
    logger.put("logger", loggerName);
    logger.put("level", level != null ? level.toString() : null);
    logger.put("group", Boolean.FALSE);

    List<Map<String, Object>> loggers = new ArrayList<>();
    loggers.add(logger);

    Map<String, Object> data = new HashMap<>();
    data.put("loggers", loggers);

    CommandEvent update = new CommandEvent(LogCollector.COMMAND_UPDATE, data);
    remoteMonitoringService.emit(update);
  }

  private static void logAtLevel(Logger logger, Level level, String message) {
    switch (level) {
      case DEBUG -> logger.debug(message);
      case INFO -> logger.info(message);
      case WARN -> logger.warn(message);
      case ERROR -> logger.error(message);
      default -> logger.trace(message);
    }
  }
}
