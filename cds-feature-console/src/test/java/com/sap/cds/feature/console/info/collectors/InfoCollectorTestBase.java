package com.sap.cds.feature.console.info.collectors;

import static com.sap.cds.feature.console.service.RemoteMonitoringConfiguration.COMMAND_ATTACHED;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.instanceOf;

import com.sap.cds.feature.console.info.Path;
import com.sap.cds.feature.console.service.CommandEventContext;
import com.sap.cds.feature.console.service.InfoEvent;
import com.sap.cds.feature.console.service.InfoEventContext;
import com.sap.cds.feature.console.service.RemoteMonitoringService;
import com.sap.cds.services.application.ApplicationLifecycleService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.HandlerOrder;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.runtime.CdsRuntime;
import com.sap.cds.services.runtime.CdsRuntimeConfigurer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.event.Level;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InfoCollectorTestBase {

  CdsRuntime runtime;
  RemoteMonitoringService remoteMonitoringService;
  RemoteMonitoringTestHandler remoteMonitoringTestHandler;

  @BeforeAll
  void prepare() {
    this.remoteMonitoringTestHandler = new RemoteMonitoringTestHandler();

    CdsRuntimeConfigurer cfg =
        CdsRuntimeConfigurer.create()
            .serviceConfigurations()
            .eventHandlerConfigurations()
            .eventHandler(this.remoteMonitoringTestHandler);
    this.runtime = cfg.complete();
    this.runtime.getServiceCatalog()
        .getService(ApplicationLifecycleService.class, ApplicationLifecycleService.DEFAULT_NAME)
        .applicationPrepared();

    this.remoteMonitoringService = runtime
        .getServiceCatalog()
        .getService(RemoteMonitoringService.class, RemoteMonitoringService.DEFAULT_NAME);
    this.remoteMonitoringService.emit(CommandEventContext.create(COMMAND_ATTACHED));
  }

  @AfterAll
  void tearDown() throws Exception {
    if (remoteMonitoringService != null && remoteMonitoringService.getRemoteMonitoringServer() != null) {
      remoteMonitoringService.getRemoteMonitoringServer().stop();
    }
  }

  @ServiceName(RemoteMonitoringService.DEFAULT_NAME)
  static class RemoteMonitoringTestHandler implements EventHandler {
    private final List<InfoEvent> infoEvents;
    private final Map<String, Object> systemData;

    public RemoteMonitoringTestHandler() {
      this.infoEvents = new ArrayList<>();
      this.systemData = new HashMap<>();
    }

    @On
    @HandlerOrder(HandlerOrder.BEFORE)
    public void on(InfoEventContext context) {
      InfoEvent infoEvent = context.getInfoEvent();
      infoEvents.add(infoEvent);
      if ("system".equals(infoEvent.getPath())) {
        systemData.putAll(infoEvent.getData());
      }
      context.setCompleted();
    }

    public void resetInfoEventsStore() {
      infoEvents.clear();
    }

    public List<InfoEvent> getEvents(String path) {
      return infoEvents.stream()
          .filter(ev -> ev.getPath().equals(path))
          .toList();
    }

    public List<InfoEvent> getLogEvents() {
      return getEvents(Path.TRACES_OUTPUT).stream()
          .filter(ev -> ev.getData().get("type").toString().equals("log"))
          .toList();
    }

    public List<InfoEvent> getLogEventsByMessage(String message) {
      return getEvents(Path.TRACES_OUTPUT).stream()
          .filter(
              ev ->
                  ev.getData().get("type").toString().equals("log")
                      && ev.getData().get("message").toString().equals(message))
          .toList();
    }

    public List<InfoEvent> getLogEventsByLevel(Level level) {
      return getEvents(Path.TRACES_OUTPUT).stream()
          .filter(
              ev ->
                  ev.getData().get("type").toString().equals("log")
                      && ev.getData().get("level").toString().equals(level.toString()))
          .toList();
    }

    public List<InfoEvent> getSysOutEvents() {
      return getEvents(Path.TRACES_OUTPUT).stream()
          .filter(ev -> ev.getData().get("type").toString().equals("out"))
          .toList();
    }

    public List<InfoEvent> getConfigEvents() {
      return getEvents(Path.SYSTEM).stream()
          .filter(ev -> ev.getData().containsKey("cds_properties"))
          .toList();
    }
  }

  @SuppressWarnings("unchecked")
  protected Object accessMapStructure(Object map, String... fields) {
    Object next = map;

    for (String property : fields) {
      assertThat(next, instanceOf(Map.class));
      var structure = (Map<String, Object>) next;
      assertThat(structure, hasKey(property));
      next = structure.get(property);
    }

    return next;
  }

}
