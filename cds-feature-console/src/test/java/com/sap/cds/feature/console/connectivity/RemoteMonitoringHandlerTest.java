package com.sap.cds.feature.console.connectivity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sap.cds.feature.console.info.Path;
import com.sap.cds.feature.console.service.InfoEvent;
import com.sap.cds.feature.console.service.RemoteLogData;
import com.sap.cds.feature.console.service.RemoteMonitoringService;
import com.sap.cds.services.runtime.CdsRuntime;
import com.sap.cds.services.runtime.CdsRuntimeConfigurer;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RemoteMonitoringHandlerTest {

  private RemoteMonitoringService remoteMonitoringService;
  private TestWebSocketClient logsClient;
  private TestWebSocketClient tasksClient;

  @BeforeAll
  void setupAll() throws Exception {
    CdsRuntime runtime = CdsRuntimeConfigurer.create()
        .serviceConfigurations()
        .eventHandlerConfigurations()
        .complete();
    this.remoteMonitoringService = runtime
        .getServiceCatalog()
        .getService(RemoteMonitoringService.class, RemoteMonitoringService.DEFAULT_NAME);

    int port = remoteMonitoringService.getRemoteMonitoringServer().getPort();

    logsClient = new TestWebSocketClient("ws://localhost:" + port + RemoteMonitoringServer.PATH_LOGS);
    logsClient.connectBlocking();
    assertTrue(logsClient.isOpen(), "Logs WebSocket client should be open");
    logsClient.awaitMessage(2, TimeUnit.SECONDS);

    tasksClient = new TestWebSocketClient("ws://localhost:" + port + RemoteMonitoringServer.PATH_TASKS);
    tasksClient.connectBlocking();
    assertTrue(tasksClient.isOpen(), "Tasks WebSocket client should be open");
    tasksClient.awaitMessage(2, TimeUnit.SECONDS);
  }

  @AfterAll
  void tearDownAll() throws Exception {
    if (logsClient != null) {
      logsClient.close();
    }
    if (tasksClient != null) {
      tasksClient.close();
    }
    if (remoteMonitoringService != null && remoteMonitoringService.getRemoteMonitoringServer() != null) {
      remoteMonitoringService.getRemoteMonitoringServer().stop();
    }
  }

  @BeforeEach
  void beforeEach() {
    logsClient.resetLatch();
    tasksClient.resetLatch();
  }

  @Test
  void outboxEventIsRoutedToTasksPath() throws Exception {
    InfoEvent outboxEvent = InfoEvent.create(Path.OUTBOX_TENANTS + ".t1", Map.of("entries", "[]"));

    remoteMonitoringService.emit(outboxEvent);

    String tasksMsg = tasksClient.awaitMessage(2, TimeUnit.SECONDS);
    assertNotNull(tasksMsg, "Tasks client should receive outbox event");

    ObjectMapper mapper = new ObjectMapper();
    JsonNode root = mapper.readTree(tasksMsg);
    assertTrue(root.path("path").asText().startsWith(Path.OUTBOX));

    String logsMsg = logsClient.awaitMessage(500, TimeUnit.MILLISECONDS);
    assertNull(logsMsg, "Logs client should NOT receive outbox event");
  }

  @Test
  void nonOutboxEventIsRoutedToLogsPath() throws Exception {
    RemoteLogData logData = new RemoteLogData.Builder()
        .level("INFO")
        .logger("test.logger")
        .thread("test-thread")
        .type("log")
        .message("Test log message")
        .ts(System.currentTimeMillis())
        .build();
    InfoEvent logEvent = InfoEvent.createRemoteLog("test.path", logData);

    remoteMonitoringService.emit(logEvent);

    String logsMsg = logsClient.awaitMessage(2, TimeUnit.SECONDS);
    assertNotNull(logsMsg, "Logs client should receive non-outbox event");

    ObjectMapper mapper = new ObjectMapper();
    JsonNode root = mapper.readTree(logsMsg);
    assertEquals("test.path", root.path("path").asText());

    String tasksMsg = tasksClient.awaitMessage(500, TimeUnit.MILLISECONDS);
    assertNull(tasksMsg, "Tasks client should NOT receive non-outbox event");
  }

  static class TestWebSocketClient extends WebSocketClient {
    private CountDownLatch latch = new CountDownLatch(1);
    private String message;

    TestWebSocketClient(String uri) throws Exception {
      super(new URI(uri));
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {}

    @Override
    public void onMessage(String message) {
      this.message = message;
      latch.countDown();
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {}

    @Override
    public void onError(Exception ex) {}

    String awaitMessage(long timeout, TimeUnit unit) throws InterruptedException {
      boolean received = latch.await(timeout, unit);
      return received ? message : null;
    }

    public void resetLatch() {
      latch = new CountDownLatch(1);
      message = null;
    }
  }
}
