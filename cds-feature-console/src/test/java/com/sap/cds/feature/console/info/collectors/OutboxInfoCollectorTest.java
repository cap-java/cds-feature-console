package com.sap.cds.feature.console.info.collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sap.cds.Row;
import com.sap.cds.feature.console.info.Path;
import com.sap.cds.feature.console.service.CommandEventContext;
import com.sap.cds.feature.console.service.InfoEvent;
import com.sap.cds.feature.console.service.RemoteMonitoringService;
import com.sap.cds.services.EventContext;
import com.sap.cds.services.ServiceCatalog;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.changeset.ChangeSetContext;
import com.sap.cds.services.changeset.ChangeSetListener;
import com.sap.cds.services.environment.CdsEnvironment;
import com.sap.cds.services.environment.CdsProperties;
import com.sap.cds.services.environment.CdsProperties.Outbox;
import com.sap.cds.services.environment.CdsProperties.Outbox.OutboxServiceConfig;
import com.sap.cds.services.impl.outbox.Messages;
import com.sap.cds.services.impl.outbox.persistence.PersistentOutbox;
import com.sap.cds.services.mt.TenantProviderService;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.request.RequestContext;
import com.sap.cds.services.request.UserInfo;
import com.sap.cds.services.runtime.CdsRuntime;
import com.sap.cds.services.runtime.ChangeSetContextRunner;
import com.sap.cds.services.runtime.RequestContextRunner;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class OutboxInfoCollectorTest {

  private RemoteMonitoringService service;
  private CdsRuntime runtime;
  private ServiceCatalog catalog;
  private PersistenceService persistenceService;
  private RequestContextRunner requestContextRunner;
  private ChangeSetContextRunner changeSetContextRunner;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    service = mock(RemoteMonitoringService.class);
    runtime = mock(CdsRuntime.class);
    catalog = mock(ServiceCatalog.class);
    persistenceService = mock(PersistenceService.class);
    requestContextRunner = mock(RequestContextRunner.class);
    changeSetContextRunner = mock(ChangeSetContextRunner.class);

    when(runtime.getServiceCatalog()).thenReturn(catalog);
    when(catalog.getService(PersistenceService.class, PersistenceService.DEFAULT_NAME)).thenReturn(persistenceService);

    when(runtime.requestContext()).thenReturn(requestContextRunner);
    when(requestContextRunner.systemUser()).thenReturn(requestContextRunner);
    when(requestContextRunner.systemUser(any())).thenReturn(requestContextRunner);
    doAnswer(inv -> {
      inv.getArgument(0, Consumer.class).accept(mock(RequestContext.class));
      return null;
    }).when(requestContextRunner).run(any(Consumer.class));

    when(runtime.changeSetContext()).thenReturn(changeSetContextRunner);
    doAnswer(inv -> {
      inv.getArgument(0, Consumer.class).accept(mock(ChangeSetContext.class));
      return null;
    }).when(changeSetContextRunner).run(any(Consumer.class));
  }

  private OutboxInfoCollector createCollector(boolean persistentOutboxEnabled) {
    if (persistentOutboxEnabled) {
      when(catalog.getServices(PersistentOutbox.class)).thenAnswer(inv -> Stream.of(mock(PersistentOutbox.class)));
    } else {
      when(catalog.getServices(PersistentOutbox.class)).thenAnswer(inv -> Stream.empty());
    }
    return new OutboxInfoCollector(runtime, service);
  }

  private Method getPrivateMethod(String name, Class<?>... paramTypes) throws Exception {
    Method m = OutboxInfoCollector.class.getDeclaredMethod(name, paramTypes);
    m.setAccessible(true);
    return m;
  }

  private void setLastSeenEntries(OutboxInfoCollector collector, Map<String, List<Object>> entries) throws Exception {
    Field field = OutboxInfoCollector.class.getDeclaredField("lastSeenEntries");
    field.setAccessible(true);
    field.set(collector, entries);
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void constructorInitializesService(boolean persistentOutboxEnabled) {
    OutboxInfoCollector collector = createCollector(persistentOutboxEnabled);
    assertEquals(service, collector.getRemoteMonitoringService());
  }

  @Test
  void capConsoleAttachedReturnsEarlyWhenOutboxDisabled() throws Exception {
    OutboxInfoCollector collector = createCollector(false);

    CommandEventContext ctx = mock(CommandEventContext.class);
    getPrivateMethod("capConsoleAttached", CommandEventContext.class).invoke(collector, ctx);

    verify(service, never()).emit(any(InfoEvent.class));
  }

  @Test
  void capConsoleAttachedEmitsOutboxConfigsWhenEnabled() throws Exception {
    OutboxInfoCollector collector = createCollector(true);

    when(catalog.getService(TenantProviderService.class, TenantProviderService.DEFAULT_NAME)).thenReturn(null);

    CommandEventContext ctx = mock(CommandEventContext.class);
    ServiceCatalog ctxCatalog = mock(ServiceCatalog.class);
    when(ctx.getServiceCatalog()).thenReturn(ctxCatalog);

    PersistentOutbox mockOutbox = mock(PersistentOutbox.class);
    when(mockOutbox.getName()).thenReturn("test-outbox");
    when(ctxCatalog.getServices(PersistentOutbox.class)).thenAnswer(inv -> Stream.of(mockOutbox));

    CdsRuntime ctxRuntime = mock(CdsRuntime.class);
    when(ctx.getCdsRuntime()).thenReturn(ctxRuntime);
    CdsEnvironment env = mock(CdsEnvironment.class);
    when(ctxRuntime.getEnvironment()).thenReturn(env);
    CdsProperties props = mock(CdsProperties.class);
    when(env.getCdsProperties()).thenReturn(props);
    Outbox outbox = mock(Outbox.class);
    when(props.getOutbox()).thenReturn(outbox);
    OutboxServiceConfig config = mock(OutboxServiceConfig.class);
    when(outbox.getService("test-outbox")).thenReturn(config);
    when(config.getMaxAttempts()).thenReturn(5);
    when(config.isOrdered()).thenReturn(true);
    when(config.isEnabled()).thenReturn(true);

    getPrivateMethod("capConsoleAttached", CommandEventContext.class).invoke(collector, ctx);

    verify(service, atLeastOnce()).emit(any(InfoEvent.class));
  }

  @ParameterizedTest
  @ValueSource(strings = {"resetEntry", "removeEntry"})
  void entryCommandEmitsNotification(String methodName) throws Exception {
    when(catalog.getService(TenantProviderService.class, TenantProviderService.DEFAULT_NAME)).thenReturn(null);
    CommandEventContext ctx = mock(CommandEventContext.class);
    Map<String, Object> data = new HashMap<>();
    data.put("id", "entry-1");
    data.put("target", "my-target");
    when(ctx.getData()).thenReturn(data);

    OutboxInfoCollector collector = createCollector(true);
    getPrivateMethod(methodName, CommandEventContext.class).invoke(collector, ctx);

    verify(service, atLeastOnce()).emit(any(InfoEvent.class));
  }

  @ParameterizedTest
  @ValueSource(strings = {"removeHistoryEntry", "replayEntry"})
  void commandWhenTenantNotInHistoryEmitsNotification(String methodName) throws Exception {
    when(catalog.getService(TenantProviderService.class, TenantProviderService.DEFAULT_NAME)).thenReturn(null);
    CommandEventContext ctx = mock(CommandEventContext.class);
    Map<String, Object> data = new HashMap<>();
    data.put("id", "entry-1");
    when(ctx.getData()).thenReturn(data);

    OutboxInfoCollector collector = createCollector(true);
    getPrivateMethod(methodName, CommandEventContext.class).invoke(collector, ctx);

    ArgumentCaptor<InfoEvent> captor = ArgumentCaptor.forClass(InfoEvent.class);
    verify(service).emit(captor.capture());
    assertEquals(Path.CONSOLE_NOTIFICATION, captor.getValue().getPath());
  }

  @Test
  void removeHistoryEntryRemovesMatchingEntry() throws Exception {
    when(catalog.getService(TenantProviderService.class, TenantProviderService.DEFAULT_NAME)).thenReturn(null);
    Row mockRow = mock(Row.class);
    Messages mockMsg = mock(Messages.class);
    when(mockRow.as(Messages.class)).thenReturn(mockMsg);
    when(mockMsg.getId()).thenReturn("entry-1");
    CommandEventContext ctx = mock(CommandEventContext.class);
    Map<String, Object> data = new HashMap<>();
    data.put("id", "entry-1");
    when(ctx.getData()).thenReturn(data);

    OutboxInfoCollector collector = createCollector(true);
    Map<String, List<Object>> history = new HashMap<>();
    List<Object> entries = new ArrayList<>();
    entries.add(mockRow);
    history.put(null, entries);
    setLastSeenEntries(collector, history);
    getPrivateMethod("removeHistoryEntry", CommandEventContext.class).invoke(collector, ctx);

    assertTrue(entries.isEmpty(), "History entry should have been removed");
    verify(service, atLeastOnce()).emit(any(InfoEvent.class));
  }

  @Test
  void outboxEventsSkipsReadEvent() throws Exception {
    OutboxInfoCollector collector = createCollector(true);

    EventContext ctx = mock(EventContext.class);
    when(ctx.getEvent()).thenReturn(CqnService.EVENT_READ);

    getPrivateMethod("outboxEvents", EventContext.class).invoke(collector, ctx);

    verify(service, never()).emit(any(InfoEvent.class));
  }

  @Test
  void outboxEventsRegistersListenerForNonReadEvent() throws Exception {
    when(catalog.getService(TenantProviderService.class, TenantProviderService.DEFAULT_NAME)).thenReturn(null);
    EventContext ctx = mock(EventContext.class);
    when(ctx.getEvent()).thenReturn(CqnService.EVENT_CREATE);
    UserInfo userInfo = mock(UserInfo.class);
    when(ctx.getUserInfo()).thenReturn(userInfo);
    when(userInfo.getTenant()).thenReturn("t1");
    ChangeSetContext csCtx = mock(ChangeSetContext.class);
    when(ctx.getChangeSetContext()).thenReturn(csCtx);

    OutboxInfoCollector collector = createCollector(true);
    getPrivateMethod("outboxEvents", EventContext.class).invoke(collector, ctx);

    ArgumentCaptor<ChangeSetListener> captor = ArgumentCaptor.forClass(ChangeSetListener.class);
    verify(csCtx).register(captor.capture());

    captor.getValue().afterClose(true);
    verify(service, atLeastOnce()).emit(any(InfoEvent.class));
  }

  @Test
  void getTenantOutboxesWithDisabledOutbox() throws Exception {
    OutboxInfoCollector collector = createCollector(false);

    InfoEvent event = (InfoEvent) getPrivateMethod("getTenantOutboxes", String.class).invoke(collector, "t1");

    assertEquals(Path.OUTBOX_TENANTS + ".t1", event.getPath());
    assertFalse(event.getData().containsKey("entries"));
  }

  @Test
  void getTenantOutboxesWithEnabledOutboxIncludesHistory() throws Exception {
    com.sap.cds.Result mockResult = mock(com.sap.cds.Result.class);
    when(mockResult.list()).thenReturn(List.of());
    when(persistenceService.run(any(com.sap.cds.ql.cqn.CqnSelect.class))).thenReturn(mockResult);

    OutboxInfoCollector collector = createCollector(true);
    InfoEvent event = (InfoEvent) getPrivateMethod("getTenantOutboxes", String.class).invoke(collector, "t1");

    assertEquals(Path.OUTBOX_TENANTS + ".t1", event.getPath());
    assertTrue(event.getData().containsKey("history"));
  }
}
