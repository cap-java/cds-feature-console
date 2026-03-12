/*
 * © 2024 SAP SE or an SAP affiliate company. All rights reserved.
 */
package com.sap.cds.feature.dashboard.info.collectors;

import static com.sap.cds.services.impl.outbox.persistence.OutboxQueries.messagesQuery;

import com.sap.cds.Result;
import com.sap.cds.Row;
import com.sap.cds.feature.dashboard.connectivity.InfoEvent;
import com.sap.cds.feature.dashboard.info.InfoCollector;
import com.sap.cds.feature.dashboard.info.Path;
import com.sap.cds.feature.dashboard.service.DashboardCommandEventContext;
import com.sap.cds.feature.dashboard.service.DashboardService;
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
import com.sap.cds.services.outbox.OutboxMessage;
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
import org.slf4j.LoggerFactory;

@ServiceName(DashboardService.DEFAULT_NAME)
public class OutboxInfoCollector extends InfoCollector implements EventHandler {

  private static final org.slf4j.Logger logger = LoggerFactory.getLogger(InfoCollector.class);

  public static final String TYPE = "outbox";

  public static final String COMMAND_CREATE = TYPE + "/create";
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
  private Map<String, Object> outboxes;

  public OutboxInfoCollector(CdsRuntime runtime, DashboardService dashboardService) {
    super(runtime, dashboardService);
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

  @After(event = ClientInfoCollector.COMMAND_ATTACHED)
  private void dashboardAttached(DashboardCommandEventContext context) {
    outboxes = new HashMap<>();
    context
        .getServiceCatalog()
        .getServices(PersistentOutbox.class)
        .forEach(
            box ->
                outboxes.put(
                    box.getName(),
                    context
                        .getCdsRuntime()
                        .getEnvironment()
                        .getCdsProperties()
                        .getOutbox()
                        .getService(box.getName())));
    emitInfoDashboardEvent(() -> getStatus());
    emitInfoDashboardEvent(() -> getActiveStatus());
    if (MultitenancyInfoCollector.isMtEnabled(getRuntime())) {
      List<String> tenants = getTenantService().readTenants();
      tenants.forEach(
          tenant -> {
            emitInfoDashboardEvent(() -> getTenantStatus(tenant));
            emitInfoDashboardEvent(() -> getLastSeenEntries(tenant));
          });
    } else {
      emitInfoDashboardEvent(() -> getTenantStatus(null));
      emitInfoDashboardEvent(() -> getLastSeenEntries(null));
    }
  }

  private String getTenant(DashboardCommandEventContext context) {
    if (MultitenancyInfoCollector.isMtEnabled(getRuntime())) {
      return (String) context.getData().get("tenant");
    }
    return null;
  }

  @After(event = COMMAND_START_COLLECTOR)
  private void startCollector(DashboardCommandEventContext context) {
    String outboxName = (String) context.getData().get("name");
    PersistentOutbox outbox =
        getRuntime().getServiceCatalog().getService(PersistentOutbox.class, outboxName);
    outbox.start();
    for (int i = 0; i < 10; i++) {
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        //
      }
      if (outbox.isCollectorRunning()) {
        emitInfoDashboardEvent(() -> getActiveStatus());
        return;
      }
    }
  }

  @After(event = COMMAND_STOP_COLLECTOR)
  private void stopCollector(DashboardCommandEventContext context) {
    String outboxName = (String) context.getData().get("name");
    PersistentOutbox outbox =
        getRuntime().getServiceCatalog().getService(PersistentOutbox.class, outboxName);
    outbox.stop();
    emitInfoDashboardEvent(() -> getActiveStatus());
  }

  @SuppressWarnings("unchecked")
  @After(event = COMMAND_CREATE)
  private void createEntry(DashboardCommandEventContext context) {
    sendInfoNotification("Outbox Entry Create", "Creating the outbox entry...");
    String outbox = (String) context.getData().get("outbox");
    String tenant = getTenant(context);
    String event = (String) context.getData().get("event");
    OutboxMessage msg = OutboxMessage.create();
    Map<String, Object> payload = (Map<String, Object>) context.getData().get("payload");
    if (payload != null && payload.containsKey("event")) {
      msg.setEvent((String) payload.remove("event"));
      msg.setParams((Map<String, Object>) payload.get("params"));
    }

    getRuntime()
        .requestContext()
        .systemUser(tenant)
        .run(
            r -> {
              r.getServiceCatalog().getService(PersistentOutbox.class, outbox).submit(event, msg);
            });
  }

  @After(event = COMMAND_RESET)
  private void resetEntry(DashboardCommandEventContext context) {
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
                          InfoCollector.inDashboardContext(
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
  private void removeEntry(DashboardCommandEventContext context) {
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
                          InfoCollector.inDashboardContext(
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
  private void replayEntry(DashboardCommandEventContext context) {
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
                                        String appid =
                                            getRuntime()
                                                .getEnvironment()
                                                .getCdsProperties()
                                                .getEnvironment()
                                                .getDeployment()
                                                .getAppid();
                                        if (appid != null) {
                                          newMsg.setAppid(appid);
                                        }

                                        InfoCollector.inDashboardContext(
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
        emitInfoDashboardEvent(() -> getLastSeenEntries(tenant));
      }
    }
  }

  @After(event = COMMAND_REMOVE_HISTORY)
  private void removeHistoryEntry(DashboardCommandEventContext context) {
    sendInfoNotification(
        "Outbox Entry Remove History", "Removing the outbox entry from history...");
    String id = (String) context.getData().get("id");
    String tenant = getTenant(context);
    synchronized (lastSeenEntries) {
      if (lastSeenEntries.containsKey(tenant)) {
        lastSeenEntries
            .get(tenant)
            .removeIf(entry -> ((Row) entry).as(Messages.class).getId().equals(id));
        emitInfoDashboardEvent(() -> getLastSeenEntries(tenant));
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
                        InfoCollector.inDashboardContext(
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
                  emitInfoDashboardEvent(() -> getTenantStatus(tenant));
                  if (event.equals(CqnService.EVENT_DELETE)) {
                    emitInfoDashboardEvent(() -> getLastSeenEntries(tenant));
                  }
                }
              });
    }
  }

  @After(service = DeploymentService.DEFAULT_NAME)
  private void tenantSubscribed(SubscribeEventContext context) {
    getTenantService()
        .readTenants()
        .forEach(tenant -> emitInfoDashboardEvent(() -> getTenantStatus(tenant)));
  }

  InfoEvent getStatus() {
    InfoEvent event = InfoEvent.create(Path.OUTBOX);
    if (isPersistentOutboxEnabled) {
      event.getData().put("enabled", true);
      event.getData().put("outboxes", outboxes);
    } else {
      event.getData().put("enabled", false);
    }
    return event;
  }

  InfoEvent getActiveStatus() {
    InfoEvent event = InfoEvent.create(Path.OUTBOX);
    if (isPersistentOutboxEnabled) {
      Map<String, Boolean> active = new HashMap<>();
      getRuntime()
          .getServiceCatalog()
          .getServices(PersistentOutbox.class)
          .filter(s -> s.isCollectorRunning())
          .forEach(s -> active.put(s.getName(), s.isCollectorRunning()));
      event.getData().put("active", active);
    } else {
      event.getData().put("enabled", false);
    }
    return event;
  }

  InfoEvent getTenantStatus(String tenant) {
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
                          InfoCollector.inDashboardContext(
                              () -> {
                                CqnSelect select =
                                    messagesQuery(
                                        getRuntime(), s -> s.orderBy(e -> e.timestamp().asc()));
                                Result res = persistenceService.run(select);
                                res.forEach(entry -> updateOutboxEntry(entry));
                                event.getData().put("entries", res.list());
                              });
                        });
              });
    } else {
      event.getData().put("enabled", false);
    }
    return event;
  }

  private void updateOutboxEntry(Row row) {
    Map<String, Object> data = row;
    data.put("jsonMsg", CloudEventUtils.toMap((String) data.get("msg")));
  }

  InfoEvent getLastSeenEntries(String tenant) {
    InfoEvent event = InfoEvent.create(Path.OUTBOX_TENANTS + '.' + tenant);
    if (isPersistentOutboxEnabled) {
      event.getData().put("enabled", true);
      List<Object> reversedList;
      synchronized (lastSeenEntries) {
        if (lastSeenEntries.get(tenant) != null) {
          reversedList = lastSeenEntries.get(tenant).subList(0, lastSeenEntries.get(tenant).size());
        } else {
          reversedList = new ArrayList<>();
        }
      }
      Collections.reverse(reversedList);
      event.getData().put("history", reversedList);
    } else {
      event.getData().put("enabled", false);
    }
    return event;
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
