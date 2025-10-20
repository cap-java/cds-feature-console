package com.sap.cds.feature.console.info.collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.sap.cds.feature.console.service.CommandEvent;
import com.sap.cds.feature.console.service.InfoEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

class LogCollectorTest extends InfoCollectorTestBase {

  private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(LogCollectorTest.class);

  @AfterEach
  void resetLogEventsStore() {
    remoteMonitoringTestHandler.resetInfoEventsStore();
    updateLogLevel(null, LOG.getName());
    remoteMonitoringTestHandler.resetInfoEventsStore();
    List<InfoEvent> logs = remoteMonitoringTestHandler.getLogEvents();
    assertEquals(0, logs.size());
  }

  @Test
  void testUpdateLogLevelsToInfo() {
    updateLogLevel(Level.INFO, LOG.getName());
    LOG.info("info log");
    LOG.debug("debug log");

    assertEquals(1, remoteMonitoringTestHandler.getLogEventsByLevel(Level.INFO).size());
    assertEquals(0, remoteMonitoringTestHandler.getLogEventsByLevel(Level.DEBUG).size());
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

  @Test
  void testUpdateLogLevelsToError() {
    updateLogLevel(Level.ERROR, LOG.getName());

    LOG.info("info log");
    LOG.debug("debug log");
    System.out.println("out");

    assertEquals(0, remoteMonitoringTestHandler.getLogEventsByLevel(Level.INFO).size());
    assertEquals(0, remoteMonitoringTestHandler.getLogEventsByLevel(Level.DEBUG).size());
  }

  @Test
  void testUpdateLogLevelsForCdsLoggerGroup() {
    Map<String, Object> logger = new HashMap<>();
    logger.put("logger", "handlers"); // "handlers" is a CdsLoggerGroup value
    logger.put("level", Level.WARN.toString());
    logger.put("group", Boolean.TRUE);

    List<Map<String, Object>> loggers = new ArrayList<>();
    loggers.add(logger);
    Map<String, Object> data = new HashMap<>();
    data.put("loggers", loggers);

    CommandEvent update = new CommandEvent(LogCollector.COMMAND_UPDATE, data);
    remoteMonitoringService.emit(update);

    Logger handlersLogger = LoggerFactory.getLogger("com.sap.cds.services.impl.ServiceImpl");
    handlersLogger.warn("warn log for CdsLoggerGroup");
    handlersLogger.info("info log for CdsLoggerGroup");

    assertEquals(1, remoteMonitoringTestHandler.getLogEventsByLevel(Level.WARN).size());
    assertEquals(0, remoteMonitoringTestHandler.getLogEventsByLevel(Level.INFO).size());

    // reset
    remoteMonitoringTestHandler.resetInfoEventsStore();
    logger.put("level", null);
    remoteMonitoringService.emit(update);
    handlersLogger.info("debug log for CdsLoggerGroup");

    assertEquals(1, remoteMonitoringTestHandler.getLogEventsByLevel(Level.INFO).size());
  }

  @Test
  void testUpdateLogLevelsForGroup() {
    Map<String, Object> logger = new HashMap<>();
    logger.put("logger", "com.sap.cds.feature.console");
    logger.put("level", Level.DEBUG.toString());
    logger.put("group", Boolean.FALSE);

    List<Map<String, Object>> loggers = new ArrayList<>();
    loggers.add(logger);
    Map<String, Object> data = new HashMap<>();
    data.put("loggers", loggers);

    CommandEvent update = new CommandEvent(LogCollector.COMMAND_UPDATE, data);
    remoteMonitoringService.emit(update);

    LOG.debug("debug log for group");

    assertEquals(1, remoteMonitoringTestHandler.getLogEventsByMessage("debug log for group").size());

    // reset
    remoteMonitoringTestHandler.resetInfoEventsStore();
    logger.put("level", null);
    remoteMonitoringService.emit(update);
    LOG.debug("debug log for group");

    assertEquals(0, remoteMonitoringTestHandler.getLogEventsByLevel(Level.DEBUG).size());
  }

  @SuppressWarnings("unchecked")
  private void updateLogLevel(Level level, String loggerName) {
    Map<String, Object> logger = new HashMap<>();
    logger.put("logger", loggerName);
    logger.put("level", level != null ? level.toString() : null);
    logger.put("group", Boolean.FALSE);

    List<Map<String, Object>> loggers = new ArrayList<Map<String, Object>>();
    loggers.add(logger);

    Map<String, Object> data = new HashMap<>();
    data.put("loggers", loggers);

    CommandEvent update = new CommandEvent(LogCollector.COMMAND_UPDATE, data);
    remoteMonitoringService.emit(update);
  }

}
