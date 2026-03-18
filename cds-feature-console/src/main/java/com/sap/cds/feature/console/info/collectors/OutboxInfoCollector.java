package com.sap.cds.feature.console.info.collectors;

import static com.sap.cds.feature.console.service.RemoteMonitoringConfiguration.COMMAND_ATTACHED;

import com.sap.cds.Result;
import com.sap.cds.Row;
import com.sap.cds.feature.console.info.InfoCollector;
import com.sap.cds.feature.console.info.Path;
import com.sap.cds.feature.console.service.CommandEventContext;
import com.sap.cds.feature.console.service.InfoEvent;
import com.sap.cds.feature.console.service.OutboxConfig;
import com.sap.cds.feature.console.service.RemoteMonitoringService;
import com.sap.cds.ql.Delete;
import com.sap.cds.ql.Insert;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.ql.cqn.CqnAnalyzer;
import com.sap.cds.ql.cqn.CqnSelect;
import com.sap.cds.services.EventContext;
import com.sap.cds.services.cds.CdsDeleteEventContext;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.changeset.ChangeSetListener;
import com.sap.cds.services.environment.CdsProperties.Outbox.OutboxServiceConfig;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.After;
import com.sap.cds.services.handler.annotations.Before;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.impl.outbox.Messages;
import com.sap.cds.services.impl.outbox.Messages_;
import com.sap.cds.services.impl.outbox.persistence.PersistentOutbox;
import com.sap.cds.services.impl.outbox.persistence.collectors.PartitionCollector;
import com.sap.cds.services.messaging.utils.CloudEventUtils;
import com.sap.cds.services.mt.DeploymentService;
import com.sap.cds.services.mt.SubscribeEventContext;
import com.sap.cds.services.mt.TenantProviderService;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.runtime.CdsRuntime;
import com.sap.cds.services.utils.outbox.OutboxUtils;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.LoggerFactory;

@ServiceName(RemoteMonitoringService.DEFAULT_NAME)
public class OutboxInfoCollector extends InfoCollector implements EventHandler {

  private static final org.slf4j.Logger logger = LoggerFactory.getLogger(InfoCollector.class);

  public static final String TYPE = "outbox";

  public static final String COMMAND_RESET = TYPE + "/reset";
  public static final String COMMAND_REMOVE = TYPE + "/remove";
  public static final String COMMAND_REPLAY = TYPE + "/replay";
  public static final String COMMAND_REMOVE_HISTORY = TYPE + "/remove-history";
  public static final String COMMAND_START_COLLECTOR = TYPE + "/start-collector";
  public static final String COMMAND_STOP_COLLECTOR = TYPE + "/stop-collector";

  private static final int MAX_HISTORY = 25;

  private PersistenceService persistenceService;
  private boolean isPersistentOutboxEnabled;

  private Map<String, List<Object>> lastSeenEntries = new HashMap<>();

  public OutboxInfoCollector(CdsRuntime runtime, RemoteMonitoringService remoteMonitoringService) {
    super(runtime, remoteMonitoringService);
    persistenceService =
        runtime
            .getServiceCatalog()
            .getService(PersistenceService.class, PersistenceService.DEFAULT_NAME);
    isPersistentOutboxEnabled =
        runtime.getServiceCatalog().getServices(PersistentOutbox.class).count() > 0;
  }

  private TenantProviderService getTenantService() {
    return getRuntime()
        .getServiceCatalog()
        .getService(TenantProviderService.class, TenantProviderService.DEFAULT_NAME);
  }

  @After(event = COMMAND_ATTACHED)
  private void capConsoleAttached(CommandEventContext context) {
    if (!isPersistentOutboxEnabled) {
      return;
    }

    List<OutboxConfig> outBoxConfigs =
        context
            .getServiceCatalog()
            .getServices(PersistentOutbox.class)
            .map(
                box -> {
                  OutboxServiceConfig config =
                      context
                          .getCdsRuntime()
                          .getEnvironment()
                          .getCdsProperties()
                          .getOutbox()
                          .getService(box.getName());
                  return OutboxConfig.fromServiceConfig(config, box.getName());
                })
            .collect(Collectors.toList());

    InfoEvent outboxConfigsEvent = InfoEvent.create(Path.OUTBOX, Map.of("outboxes", outBoxConfigs));
    emitInfoEvent(() -> outboxConfigsEvent);

    if (isMultitenancyEnabled()) {
      List<String> tenants = getTenantService().readTenants();
      tenants.forEach(tenant -> emitInfoEvent(() -> getTenantOutboxes(tenant)));
    } else {
      emitInfoEvent(() -> getTenantOutboxes(null));
    }
  }

  private boolean isMultitenancyEnabled() {
    return getRuntime()
            .getServiceCatalog()
            .getService(TenantProviderService.class, TenantProviderService.DEFAULT_NAME)
        != null;
  }

  private String getTenant(CommandEventContext context) {
    if (isMultitenancyEnabled()) {
      return (String) context.getData().get("tenant");
    }
    return null;
  }

  @After(event = COMMAND_RESET)
  private void resetEntry(CommandEventContext context) {
    sendInfoNotification("Outbox Entry Reset", "Resetting the outbox entry...");
    String id = (String) context.getData().get("id");
    String tenant = getTenant(context);
    String target = (String) context.getData().get("target");
    getRuntime()
        .requestContext()
        .systemUser(tenant)
        .run(
            r -> {
              getRuntime()
                  .changeSetContext()
                  .run(
                      ch -> {
                        if (target != null) {
                          ch.register(
                              new ChangeSetListener() {
                                @Override
                                public void afterClose(boolean completed) {
                                  scheduleOutbox(context.getCdsRuntime(), target);
                                }
                              });
                        }
                        try {
                          InfoCollector.inRemoteMonitoringContext(
                              () ->
                                  persistenceService.run(
                                      Update.entity(Messages_.class)
                                          .data(Messages.ATTEMPTS, 0)
                                          .where(m -> m.ID().eq(id))));
                          sendSuccessNotification(
                              "Outbox Entry Reset", "Outbox entry successfully reset!");
                        } catch (Throwable th) {
                          sendErrorNotification(
                              "Error Outbox Entry Reset",
                              "Entry could not be reset '%s'",
                              th.getMessage());
                        }
                      });
            });
  }

  @After(event = COMMAND_REMOVE)
  private void removeEntry(CommandEventContext context) {
    sendInfoNotification("Outbox Entry Remove", "Removing the outbox entry...");
    String id = (String) context.getData().get("id");
    String tenant = getTenant(context);
    getRuntime()
        .requestContext()
        .systemUser(tenant)
        .run(
            r -> {
              getRuntime()
                  .changeSetContext()
                  .run(
                      ch -> {
                        try {
                          InfoCollector.inRemoteMonitoringContext(
                              () ->
                                  persistenceService.run(
                                      Delete.from(Messages_.class).where(e -> e.ID().eq(id))));
                          sendSuccessNotification(
                              "Outbox Entry Remove", "Outbox entry successfully removed!");
                        } catch (Throwable th) {
                          sendErrorNotification(
                              "Error Outbox Entry Remove",
                              "Entry could not be removed '%s'",
                              th.getMessage());
                        }
                      });
            });
  }

  @After(event = COMMAND_REPLAY)
  private void replayEntry(CommandEventContext context) {
    sendInfoNotification("Outbox Entry Replay", "Replaying the outbox entry...");
    String id = (String) context.getData().get("id");
    String tenant = getTenant(context);
    synchronized (lastSeenEntries) {
      if (lastSeenEntries.containsKey(tenant)) {
        List<String> removedFromHistory = new ArrayList<>();
        lastSeenEntries.get(tenant).stream()
            .map(e -> ((Row) e).as(Messages.class))
            .filter(msg -> msg.getId().equals(id))
            .forEach(
                msg -> {
                  getRuntime()
                      .requestContext()
                      .systemUser(tenant)
                      .run(
                          req -> {
                            getRuntime()
                                .changeSetContext()
                                .run(
                                    ch -> {
                                      try {

                                        Messages newMsg = Messages.create();
                                        newMsg.setMsg(msg.getMsg());
                                        newMsg.setTarget(msg.getTarget());
                                        newMsg.setTimestamp(Instant.now());

                                        InfoCollector.inRemoteMonitoringContext(
                                            () ->
                                                persistenceService.run(
                                                    Insert.into(Messages_.class).entry(newMsg)));
                                        removedFromHistory.add(msg.getId());
                                        scheduleOutbox(context.getCdsRuntime(), msg.getTarget());
                                        sendSuccessNotification(
                                            "Outbox Entry Replayed",
                                            "Outbox entry successfully replayed!");

                                      } catch (Throwable th) {
                                        sendErrorNotification(
                                            "Error Outbox Entry Replay",
                                            "Entry could not be replayed '%s'",
                                            th.getMessage());
                                      }
                                    });
                          });
                });
        removedFromHistory.forEach(
            msg -> lastSeenEntries.get(tenant).removeIf(entry -> entry.equals(msg)));
        emitInfoEvent(() -> getTenantOutboxes(tenant));
      }
    }
  }

  @After(event = COMMAND_REMOVE_HISTORY)
  private void removeHistoryEntry(CommandEventContext context) {
    sendInfoNotification(
        "Outbox Entry Remove History", "Removing the outbox entry from history...");
    String id = (String) context.getData().get("id");
    String tenant = getTenant(context);
    synchronized (lastSeenEntries) {
      if (lastSeenEntries.containsKey(tenant)) {
        lastSeenEntries
            .get(tenant)
            .removeIf(entry -> ((Row) entry).as(Messages.class).getId().equals(id));
        emitInfoEvent(() -> getTenantOutboxes(tenant));
      }
    }
  }

  @Before(
      service = PersistenceService.DEFAULT_NAME,
      entity = OutboxUtils.OUTBOX_MODEL,
      event = CqnService.EVENT_DELETE)
  private void outboxEventDelete(CdsDeleteEventContext context) {
    String tenant = context.getUserInfo().getTenant();
    getRuntime()
        .requestContext()
        .systemUser(tenant)
        .run(
            req -> {
              getRuntime()
                  .changeSetContext()
                  .run(
                      ch -> {
                        CqnAnalyzer analyzer = CqnAnalyzer.create(context.getModel());
                        String id =
                            (String)
                                analyzer.analyze(context.getCqn()).targetKeys().get(Messages.ID);
                        CqnSelect select = Select.from(Messages_.class).where(e -> e.ID().eq(id));
                        InfoCollector.inRemoteMonitoringContext(
                            () -> {
                              persistenceService
                                  .run(select)
                                  .forEach(
                                      row -> {
                                        synchronized (lastSeenEntries) {
                                          if (lastSeenEntries.get(tenant) == null) {
                                            lastSeenEntries.put(tenant, new ArrayList<>());
                                          }
                                          List<Object> history = lastSeenEntries.get(tenant);
                                          if (!history.stream()
                                              .anyMatch(
                                                  o ->
                                                      ((Row) o)
                                                              .as(Messages.class)
                                                              .getMsg()
                                                              .equals(
                                                                  row.as(Messages.class).getMsg())
                                                          && ((Row) o)
                                                              .as(Messages.class)
                                                              .getTarget()
                                                              .equals(
                                                                  row.as(Messages.class)
                                                                      .getTarget()))) {
                                            updateOutboxEntry(row);
                                            history.add(row);
                                            if (history.size() > MAX_HISTORY) {
                                              history.remove(0);
                                            }
                                          }
                                        }
                                      });
                            });
                      });
            });
  }

  @After(service = PersistenceService.DEFAULT_NAME, entity = OutboxUtils.OUTBOX_MODEL, event = "*")
  private void outboxEvents(EventContext context) {
    String event = context.getEvent();
    if (!event.equals(CqnService.EVENT_READ)) {
      String tenant = context.getUserInfo().getTenant();
      context
          .getChangeSetContext()
          .register(
              new ChangeSetListener() {

                @Override
                public void afterClose(boolean completed) {
                  emitInfoEvent(() -> getTenantOutboxes(tenant));
                }
              });
    }
  }

  @After(service = DeploymentService.DEFAULT_NAME)
  private void tenantSubscribed(SubscribeEventContext context) {
    getTenantService()
        .readTenants()
        .forEach(tenant -> emitInfoEvent(() -> getTenantOutboxes(tenant)));
  }

  private InfoEvent getTenantOutboxes(String tenant) {
    InfoEvent event = InfoEvent.create(Path.OUTBOX_TENANTS + '.' + tenant);
    if (isPersistentOutboxEnabled) {
      getRuntime()
          .requestContext()
          .systemUser(tenant)
          .run(
              r -> {
                getRuntime()
                    .changeSetContext()
                    .run(
                        ch -> {
                          InfoCollector.inRemoteMonitoringContext(
                              () -> {
                                CqnSelect select =
                                    Select.from(Messages_.class).orderBy(e -> e.timestamp().asc());
                                Result res = persistenceService.run(select);
                                res.forEach(entry -> updateOutboxEntry(entry));
                                event.getData().put("entries", res.list());
                              });
                        });
              });

      // Add history
      List<Object> reversedList;
      synchronized (lastSeenEntries) {
        reversedList =
            lastSeenEntries.get(tenant) != null
                ? new ArrayList<>(lastSeenEntries.get(tenant))
                : new ArrayList<>();
      }
      Collections.reverse(reversedList);
      event.getData().put("history", reversedList);
    }

    return event;
  }

  private void updateOutboxEntry(Row row) {
    Map<String, Object> data = row;
    data.put("jsonMsg", CloudEventUtils.toMap((String) data.get("msg")));
  }

  private void scheduleOutbox(CdsRuntime runtime, String target) {
    if (isPersistentOutboxEnabled) {
      runtime
          .getServiceCatalog()
          .getServices(PersistentOutbox.class)
          .filter(s -> s.getName().endsWith(target))
          .forEach(this::scheduleCollector);
    }
  }

  private void scheduleCollector(PersistentOutbox outbox) {
    try {
      Field collector = outbox.getClass().getDeclaredField("collector");
      collector.setAccessible(true);
      Object collectorInstance = collector.get(outbox);

      // Check if it's a PartitionCollector (which has unpause method)
      if (collectorInstance instanceof PartitionCollector) {
        Method unpause = PartitionCollector.class.getDeclaredMethod("unpause");
        unpause.setAccessible(true);
        unpause.invoke(collectorInstance);
      }
      // For TaskBasedCollector, no manual scheduling is needed as it's task-based
      // and will automatically pick up new messages through its scheduled tasks
    } catch (Exception e) {
      logger.error("Cannot schedule the collector for the outbox {}", outbox.getName(), e);
    }
  }
}
