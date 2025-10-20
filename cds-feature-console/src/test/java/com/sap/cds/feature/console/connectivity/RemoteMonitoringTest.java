package com.sap.cds.feature.console.connectivity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sap.cds.feature.console.service.InfoEvent;
import com.sap.cds.feature.console.service.RemoteLogData;
import com.sap.cds.feature.console.service.RemoteMonitoringService;
import com.sap.cds.services.EventContext;
import com.sap.cds.services.runtime.CdsRuntime;
import com.sap.cds.services.runtime.CdsRuntimeConfigurer;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RemoteMonitoringTest {

  private static final int PORT = 54953; // must match RemoteMonitoringConfiguration
  private RemoteMonitoringService remoteMonitoringService;
  private TestWebSocketClient client;

  @BeforeAll
  void setupAll() throws Exception {
    CdsRuntime runtime = CdsRuntimeConfigurer.create()
        .serviceConfigurations()
        .eventHandlerConfigurations()
        .complete();
    this.remoteMonitoringService = runtime
      .getServiceCatalog()
      .getService(RemoteMonitoringService.class, RemoteMonitoringService.DEFAULT_NAME);

    client = new TestWebSocketClient("ws://localhost:" + PORT + "/cap-console/logs");
    client.connectBlocking();
    assertTrue(client.isOpen(), "WebSocket client should be open");

    String receivedWelcome = client.awaitMessage(2, TimeUnit.SECONDS);
    assertNotNull(receivedWelcome, "Client should receive the broadcast message");
    ObjectMapper mapper = new ObjectMapper();
    JsonNode root = mapper.readTree(receivedWelcome);
    assertEquals("/cap-console/logs", root.path("path").asText(), "Expected path to be /cap-console/logs");
    assertEquals("welcome", root.path("data").path("type").asText(), "Expected type to be welcome");
  }

  @AfterAll
  void tearDownAll() throws InterruptedException {
    if (client != null) {
      client.close();
    }
    if (remoteMonitoringService != null && remoteMonitoringService.getRemoteMonitoringServer() != null) {
      remoteMonitoringService.getRemoteMonitoringServer().stop();
    }
  }

  @BeforeEach
  void beforeEach() {
    client.resetLatch();
  }

  @Test
  void testLogEventBroadcastedViaWebSocket() throws Exception {
    // Simulate a log event via CAP service using structured data
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

    // Wait for the message to be received
    String received = client.awaitMessage(2, TimeUnit.SECONDS);
    assertNotNull(received, "Client should receive the broadcast message");
    
    ObjectMapper mapper = new ObjectMapper();
    JsonNode root = mapper.readTree(received);
    assertEquals("test.path", root.path("path").asText());
    JsonNode data = root.path("data");
    assertEquals("INFO", data.path("level").asText());
    assertEquals("test.logger", data.path("logger").asText());
    assertEquals("test-thread", data.path("thread").asText());
    assertEquals("log", data.path("type").asText());
    assertEquals("Test log message", data.path("message").asText());
    assertTrue(data.path("ts").isNumber());
  }

  @Test
  void testUpdateLogLevelsCommandEmitsCommandEvent() throws Exception {
    // Prepare a valid CommandEvent JSON with command and data
    String json = """
        {
          "command": "logging/update",
          "data": {
            "loggers": [
              {
                "logger": "com.sap.cds.feature.console.service.RemoteMonitoringConfiguration",
                "level": "DEBUG",
                "group": false
              }
            ]
          }
        }
        """;

    // Set up a listener/handler to capture emitted CommandEvents
    AtomicReference<EventContext> emitted = new AtomicReference<>();
    remoteMonitoringService.after("logging/update", null, emitted::set);

    client.send(json);
    Thread.sleep(500);

    assertNotNull(emitted.get(), "A CommandEvent should have been emitted");
    assertEquals("logging/update", emitted.get().getEvent());

    Object dataObj = emitted.get().get("data");
    assertNotNull(dataObj, "data should not be null");

    ObjectMapper mapper = new ObjectMapper();
    String dataJson = mapper.writeValueAsString(dataObj);
    Map<String, Object> dataMap = mapper.readValue(dataJson, Map.class);

    Object loggersObj = dataMap.get("loggers");
    java.util.List<?> loggersList = (java.util.List<?>) loggersObj;
    assertEquals(1, loggersList.size());

    Map<?, ?> loggerEntry = (Map<?, ?>) loggersList.get(0);
    assertEquals("com.sap.cds.feature.console.service.RemoteMonitoringConfiguration", loggerEntry.get("logger"));
    assertEquals("DEBUG", loggerEntry.get("level"));
    assertEquals(Boolean.FALSE, loggerEntry.get("group"));
  }

  static class TestWebSocketClient extends WebSocketClient {
    private CountDownLatch latch = new CountDownLatch(1);
    private String message;

    TestWebSocketClient(String uri) throws Exception {
      super(new URI(uri));
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {

    }

    @Override
    public void onMessage(String message) {
      this.message = message;
      latch.countDown();
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {

    }

    @Override
    public void onError(Exception ex) {

    }

    public String awaitMessage(long timeout, TimeUnit unit) throws InterruptedException {
      boolean received = latch.await(timeout, unit);
      return received ? message : null;
    }

    public void resetLatch() {
      latch = new CountDownLatch(1);
      message = null;
    }
  }

}
