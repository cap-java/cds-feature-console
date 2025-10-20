package com.sap.cds.feature.console.info.collectors;

import static com.sap.cds.feature.console.service.RemoteMonitoringConfiguration.COMMAND_ATTACHED;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.CoreConstants;
import com.sap.cds.feature.console.info.InfoCollector;
import com.sap.cds.feature.console.info.Path;
import com.sap.cds.feature.console.service.CommandEventContext;
import com.sap.cds.feature.console.service.InfoEvent;
import com.sap.cds.feature.console.service.RemoteLogData;
import com.sap.cds.feature.console.service.RemoteMonitoringService;
import com.sap.cds.framework.spring.utils.CdsLoggerGroupsPostProcessor;
import com.sap.cds.services.application.ApplicationLifecycleService;
import com.sap.cds.services.application.ApplicationStoppedEventContext;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.runtime.CdsRuntime;
import com.sap.cds.services.utils.StringUtils;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

@ServiceName(RemoteMonitoringService.DEFAULT_NAME)
public class LogCollector extends InfoCollector implements EventHandler {

  private static final org.slf4j.Logger logger = LoggerFactory.getLogger(LogCollector.class);

  public static final String TYPE = "logging";
  public static final String COMMAND_UPDATE = TYPE + "/update";

  private final List<Logger> activeLoggers = new ArrayList<>();
  private final RemoteMonitoringAppender appender = new RemoteMonitoringAppender();

  private static final Map<String, String> cfgLoggingLevels = new LinkedHashMap<>();
  private static final Map<String, String[]> cfgLoggerGroups = new LinkedHashMap<>();

  public LogCollector(CdsRuntime runtime, RemoteMonitoringService remoteMonitoringService) {
    super(runtime, remoteMonitoringService);
    // initialize the CAP logger groups
    try {
      cfgLoggerGroups.putAll(getCdsLoggers());
    } catch (Exception e) {
      logger.error("Could not get CAP logger groups!", e);
    }
    // http logger by default
    Logger log = (Logger) LoggerFactory.getLogger("org.apache.http.wire");
    cfgLoggingLevels.put(
        "org.apache.http.wire",
        log != null && log.getLevel() != null ? log.getLevel().levelStr : Level.INFO.name());

    // system output; set custom print stream to capture output to stdout and stderr to display it in the CAP console
    System.setOut(new RemoteMonitoringFilterPrintStream(System.out, false, this)); // NOSONAR
    System.setErr(new RemoteMonitoringFilterPrintStream(System.err, true, this)); // NOSONAR
  }

  @On(service = ApplicationLifecycleService.DEFAULT_NAME)
  private void applicationStopped(ApplicationStoppedEventContext context) {
    if (System.out instanceof RemoteMonitoringFilterPrintStream) {
      ((RemoteMonitoringFilterPrintStream) System.out).deactivate();
      ((RemoteMonitoringFilterPrintStream) System.err).deactivate();
    }
    if (appender.isStarted()) {
      appender.stop();
    }
  }

  @On(event = COMMAND_ATTACHED)
  void capConsoleAttached(CommandEventContext context) {
    if (activeLoggers.isEmpty()) {
      appender.stop();
      Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
      root.addAppender(appender);
      appender.start();
    }
    emitInfoEvent(this::getLoggers);
    emitInfoEvent(this::getLoggerGroups);
  }

  @SuppressWarnings("unchecked")
  @On(event = COMMAND_UPDATE)
  private void updateLogLevels(CommandEventContext context) {
    super.sendInfoNotification("Setting new loggers configuration!");
    activateLoggers((List<Map<String, Object>>) context.getData().get("loggers"));
  }

  private void activateLoggers(List<Map<String, Object>> loggers) {
    activeLoggers.forEach(l -> {
      if (l.getName().equals(Logger.ROOT_LOGGER_NAME)) {
        l.setLevel(ch.qos.logback.classic.Level.INFO);
      } else {
        l.setLevel(null);
      }
    });
    activeLoggers.clear();
    cfgLoggingLevels.clear();

    loggers.forEach(
        loggr -> {
          String loggerName = (String) loggr.get("logger");
          String level = (String) loggr.get("level");
          boolean group = (Boolean) loggr.get("group");

          cfgLoggingLevels.put(loggerName, level);

          if (group) {
            String[] loggerGroup = cfgLoggerGroups.get(loggerName);
            if (loggerGroup != null) {
              for (String l : loggerGroup) {
                Logger log = (Logger) LoggerFactory.getLogger(l);
                if (!StringUtils.isEmpty(level)) {
                  log.setLevel(ch.qos.logback.classic.Level.toLevel(level));
                }
                activeLoggers.add(log);
              }
            } else {
              sendErrorNotification(
                  "Logger Group Missing",
                  "The logger group '%s' is not defined in the CAP loggers",
                  loggerName);
            }
          } else {
            Logger log = (Logger) LoggerFactory.getLogger(loggerName);
            if (!StringUtils.isEmpty(level)) {
              log.setLevel(ch.qos.logback.classic.Level.toLevel(level));
            }
            activeLoggers.add(log);
          }
        });
  }

  InfoEvent getLoggers() {
    InfoEvent result = InfoEvent.create(Path.SYSTEM);
    List<Map<String, Object>> loggers = new ArrayList<>();
    cfgLoggingLevels.forEach(
        (loggerObject, level) -> {
          if (!cfgLoggerGroups.containsKey(loggerObject)) {
            Map<String, Object> data = new HashMap<>();
            data.put("logger", loggerObject);
            data.put("level", level);
            data.put("group", false);
            loggers.add(data);
          }
        });
    result.getData().put("loggers", loggers);
    return result;
  }

  InfoEvent getLoggerGroups() {
    InfoEvent result = InfoEvent.create(Path.SYSTEM);
    List<Map<String, Object>> groups = new ArrayList<>();
    cfgLoggerGroups.forEach(
        (group, logrs) -> {
          Map<String, Object> data = new HashMap<>();
          data.put("logger", group);
          data.put("level", getLoggerLevel(group));
          data.put("group", true);
          groups.add(data);
        });
    result.getData().put("logger_groups", groups);
    return result;
  }

  private String getLoggerLevel(String group) {
    String[] loggers = cfgLoggerGroups.get(group);
    if (loggers == null || loggers.length == 0) {
      return "INFO";
    }

    Logger log = (Logger) LoggerFactory.getLogger(loggers[0]);
    if (log.getLevel() == null) {
      return ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).getLevel().toString();
    }
    return log.getLevel().toString();
  }

  private class RemoteMonitoringAppender extends AppenderBase<ILoggingEvent> {

    @Override
    protected void append(ILoggingEvent event) {
      emitInfoEvent(() -> getLogEvent(event));
    }

    InfoEvent getLogEvent(ILoggingEvent event) {
      var exceptionMessage = extractExceptionMessage(event);

      RemoteLogData logData =
          new RemoteLogData.Builder()
              .level(event.getLevel().toString())
              .logger(event.getLoggerName())
              .thread(event.getThreadName())
              .type(exceptionMessage.isPresent() ? "exception" : "log")
              .message(exceptionMessage.orElseGet(event::getFormattedMessage))
              .ts(event.getTimeStamp())
              .build();

      return InfoEvent.createRemoteLog(Path.TRACES_OUTPUT, logData);
    }

    private Optional<String> extractExceptionMessage(ILoggingEvent event) {
      if (event.getThrowableProxy() == null) {
        return Optional.empty();
      }

      StringBuilder sb = new StringBuilder();
      String throwableStr = ThrowableProxyUtil.asString(event.getThrowableProxy());
      sb.append(throwableStr);
      sb.append(CoreConstants.LINE_SEPARATOR);

      return Optional.of(sb.toString());
    }
  }

  private static class RemoteMonitoringFilterPrintStream extends PrintStream {

    LogCollector collector;
    boolean isError;

    public RemoteMonitoringFilterPrintStream(
        PrintStream out, boolean isError, LogCollector collector) {
      super(out);
      this.isError = isError;
      this.collector = collector;
    }

    void deactivate() {
      collector = null;
    }

    @Override
    public void print(String message) {
      // pass along to actual console output
      super.print(message);

      if (collector != null) {
        collector.emitInfoEvent(
            () -> {
              RemoteLogData logData = new RemoteLogData.Builder()
                  .level(isError ? "syserr" : "sysout")
                  .logger("system")
                  .thread("main")
                  .type("out")
                  .message(message)
                  .ts(System.currentTimeMillis())
                  .build();
              return InfoEvent.createRemoteLog(Path.TRACES_OUTPUT, logData);
            });
      }
    }
  }

  private static Map<String, String[]> getCdsLoggers() {
    Map<String, String[]> result = new HashMap<>();
    for (Class<?> c : CdsLoggerGroupsPostProcessor.class.getDeclaredClasses()) {
      if (c.isEnum() && c.getSimpleName().equals("CdsLoggerGroups")) {
        try {
          Method loggersMethod = c.getDeclaredMethod("loggers");
          if (loggersMethod.getReturnType().equals(String[].class)) {
            for (Object value : c.getEnumConstants()) {
              try {
                loggersMethod.setAccessible(true);
                Object loggers = loggersMethod.invoke(value);
                result.put(value.toString(), loggers != null ? (String[]) loggers : new String[0]);
              } catch (Exception e) {
                logger.error("Cannot access loggers!", e);
              }
            }
          }
        } catch (NoSuchMethodException nsme) {
          logger.error("CdsLoggerGroups enum does not have a loggers() method!", nsme);
        }
        break;
      }
    }
    return result;
  }
}
