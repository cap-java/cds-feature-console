package com.sap.cds.feature.console.info;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.sap.cds.feature.console.service.InfoEvent;
import com.sap.cds.feature.console.service.RemoteMonitoringService;
import com.sap.cds.services.runtime.CdsRuntime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

class InfoCollectorTest {

  private RemoteMonitoringService mockService;
  private TestInfoCollector collector;

  @BeforeEach
  void setUp() {
    mockService = mock(RemoteMonitoringService.class);
    CdsRuntime mockRuntime = mock(CdsRuntime.class);
    collector = new TestInfoCollector(mockRuntime, mockService);
  }

  @AfterEach
  void cleanup() {
    InfoCollector.REMOTE_MONITORING_EVENT.remove();
  }

  @Test
  void inRemoteMonitoringContextSetsFlag() {
    assertFalse(InfoCollector.isInRemoteMonitoringContext());

    AtomicBoolean flagDuringExecution = new AtomicBoolean(false);
    InfoCollector.inRemoteMonitoringContext(
        () -> flagDuringExecution.set(InfoCollector.isInRemoteMonitoringContext()));

    assertTrue(flagDuringExecution.get(), "Flag should be true during execution");
    assertFalse(InfoCollector.isInRemoteMonitoringContext(), "Flag should be reset after execution");
  }

  @Test
  void inRemoteMonitoringContextResetsAfterException() {
    assertThrows(RuntimeException.class, () ->
        InfoCollector.inRemoteMonitoringContext(() -> {
          throw new RuntimeException("test error");
        }));

    assertFalse(InfoCollector.isInRemoteMonitoringContext(),
        "Flag should be reset even after exception");
  }

  @Test
  void emitInfoEventEmitsEvent() {
    InfoEvent event = InfoEvent.create("test.path", Map.of("message", "hello"));

    collector.emitInfoEvent(() -> event);

    verify(mockService).emit(event);
  }

  static Stream<Arguments> missingMessageData() {
    Map<String, Object> emptyMsg = new HashMap<>(Map.of("message", ""));
    Map<String, Object> nullMsg = new HashMap<>();
    nullMsg.put("message", null);
    Map<String, Object> absentMsg = new HashMap<>();
    return Stream.of(
        Arguments.of("empty string", emptyMsg),
        Arguments.of("null value", nullMsg),
        Arguments.of("absent key", absentMsg));
  }

  @ParameterizedTest(name = "message={0}")
  @MethodSource("missingMessageData")
  void emitInfoEventReplacesMissingMessageWithDash(String desc, Map<String, Object> data) {
    InfoEvent event = InfoEvent.create("test.path", data);

    collector.emitInfoEvent(() -> event);

    ArgumentCaptor<InfoEvent> captor = ArgumentCaptor.forClass(InfoEvent.class);
    verify(mockService).emit(captor.capture());
    assertEquals("-", captor.getValue().getData().get("message"));
  }

  @Test
  void emitInfoEventHandlesSupplierException() {
    collector.emitInfoEvent(() -> {
      throw new RuntimeException((String) null);
    });

    verify(mockService).emit(any(InfoEvent.class));
  }

  @Test
  void sendInfoNotificationEmitsEvent() {
    collector.sendInfoNotification("Test notification %s", "arg1");

    ArgumentCaptor<InfoEvent> captor = ArgumentCaptor.forClass(InfoEvent.class);
    verify(mockService).emit(captor.capture());
    InfoEvent emitted = captor.getValue();
    assertEquals(Path.CONSOLE_NOTIFICATION, emitted.getPath());
    assertEquals("info", emitted.getData().get("type"));
    assertEquals("Test notification arg1", emitted.getData().get("message"));
  }

  @Test
  void sendErrorNotificationEmitsEvent() {
    collector.sendErrorNotification("Error Header", "Something failed: %s", "reason");

    ArgumentCaptor<InfoEvent> captor = ArgumentCaptor.forClass(InfoEvent.class);
    verify(mockService).emit(captor.capture());
    InfoEvent emitted = captor.getValue();
    assertEquals(Path.CONSOLE_NOTIFICATION, emitted.getPath());
    assertEquals("Error Header", emitted.getData().get("type"));
    assertEquals("ERROR", emitted.getData().get("level"));
    assertEquals("Something failed: reason", emitted.getData().get("message"));
  }

  /** Concrete subclass for testing the abstract InfoCollector. */
  static class TestInfoCollector extends InfoCollector {
    TestInfoCollector(CdsRuntime runtime, RemoteMonitoringService service) {
      super(runtime, service);
    }
  }
}
