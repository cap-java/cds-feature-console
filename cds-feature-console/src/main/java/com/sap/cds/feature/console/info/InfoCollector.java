package com.sap.cds.feature.console.info;

import com.sap.cds.feature.console.service.InfoEvent;
import com.sap.cds.feature.console.service.RemoteLogData;
import com.sap.cds.feature.console.service.RemoteMonitoringService;
import com.sap.cds.services.runtime.CdsRuntime;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class InfoCollector {

  public static final ThreadLocal<Boolean> REMOTE_MONITORING_EVENT =
      ThreadLocal.withInitial(() -> false);

  private static final Logger logger = LoggerFactory.getLogger(InfoCollector.class);

  private final CdsRuntime runtime;
  private final RemoteMonitoringService remoteMonitoringService;

  public enum NotificationType {
    info,
    success,
    warning,
    error,
  }

  protected InfoCollector(CdsRuntime runtime, RemoteMonitoringService remoteMonitoringService) {
    super();
    this.remoteMonitoringService = remoteMonitoringService;
    this.runtime = runtime;
  }

  public RemoteMonitoringService getRemoteMonitoringService() {
    return remoteMonitoringService;
  }

  public CdsRuntime getRuntime() {
    return runtime;
  }

  protected void emitInfoEvent(Supplier<InfoEvent> infoProducer) {
    try {
      var event = infoProducer.get();

      // TODO: Temporary workaround: sends "-" instead of an empty string to the CAP console.
      // This was introduced to maintain consistency with Cloud Logging.
      // In the future, we should investigate whether it's possible to avoid logging such messages entirely.
      if (isEventMessageNullOrEmpty(event.getData())) {
        event.getData().put("message", "-");
      }
      getRemoteMonitoringService().emit(event);
    } catch (Exception e) {
      sendErrorNotification("Data Access Error", e.getMessage());
      logger.error("Could not emit remote-monitoring info event!", e);
    }
  }

  private static boolean isEventMessageNullOrEmpty(Map<String, Object> data) {
    return !data.containsKey("message")
        || data.get("message") == null
        || "".equalsIgnoreCase(data.get("message").toString());
  }

  public void sendInfoNotification(String notification, Object... args) {
    sendNotification(NotificationType.info, notification, args);
  }

  public void sendSuccessNotification(String header, String notification, Object... args) {
    sendNotification(NotificationType.success, header, notification, args);
  }

  public void sendErrorNotification(String header, String notification, Object... args) {
    RemoteLogData logData = new RemoteLogData.Builder()
        .type(header)
        .logger("system")
        .thread(Thread.currentThread().getName())
        .level("error")
        .message(String.format(notification, args))
        .ts(System.currentTimeMillis())
        .build();

    InfoEvent event = InfoEvent.createRemoteLog(Path.CONSOLE_NOTIFICATION, logData);
    getRemoteMonitoringService().emit(event);
  }

  public void sendNotification(NotificationType type, String notification, Object... args) {
    RemoteLogData logData = new RemoteLogData.Builder()
        .type(type.name())
        .logger("system")
        .thread(Thread.currentThread().getName())
        .level("info")
        .message(String.format(notification, args))
        .ts(System.currentTimeMillis())
        .build();

    InfoEvent event = InfoEvent.createRemoteLog(Path.CONSOLE_NOTIFICATION, logData);
    getRemoteMonitoringService().emit(event);
  }

  public static boolean isInRemoteMonitoringContext() {
    return REMOTE_MONITORING_EVENT.get();
  }
}
