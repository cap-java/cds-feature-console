package com.sap.cds.feature.console.connectivity;

import static com.sap.cds.feature.console.service.RemoteMonitoringConfiguration.COMMAND_ATTACHED;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sap.cds.feature.console.service.CommandEvent;
import com.sap.cds.feature.console.service.CommandEventContext;
import com.sap.cds.feature.console.service.InfoEvent;
import com.sap.cds.feature.console.service.RemoteLogData;
import com.sap.cds.feature.console.service.RemoteMonitoringService;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebSocketServer to stream log and other info events via websocket. To be consumed by CAP console.
 * Can be manually tested with a websocket client cli like websocat: `$ websocat -v
 * ws://localhost:54953/cap-console/logs`
 */
public class RemoteMonitoringServer extends WebSocketServer {

  private static final Logger logger = LoggerFactory.getLogger(RemoteMonitoringServer.class);
  public static final String PATH_CAP_CONSOLE = "/cap-console";
  public static final String PATH_LOGS = PATH_CAP_CONSOLE + "/logs";

  private final Map<String, Set<WebSocket>> clientsByPaths = new ConcurrentHashMap<>();
  private static final ObjectMapper objectMapper = new ObjectMapper();
  private final RemoteMonitoringService remoteMonitoringService;

  private final CountDownLatch startupLatch;
  private final AtomicReference<Exception> error;

  public RemoteMonitoringServer(
      int port,
      RemoteMonitoringService remoteMonitoringService,
      CountDownLatch startupLatch,
      AtomicReference<Exception> error) {
    super(new InetSocketAddress(port));
    this.remoteMonitoringService = remoteMonitoringService;
    this.startupLatch = startupLatch;
    this.error = error;
  }

  public void broadcastToPath(String message, String path) {
    Set<WebSocket> clients = clientsByPaths.getOrDefault(path, Collections.emptySet());
    for (WebSocket client : clients) {
      client.send(message);
    }
  }

  @Override
  public void onOpen(WebSocket conn, ClientHandshake handshake) {
    String path = handshake.getResourceDescriptor(); // e.g., "/logs"
    if (PATH_LOGS.equals(path) && !clientsByPaths.containsKey(path)) {
      logger.debug("First client connected to {}. Enabling log streaming.", path);
      this.remoteMonitoringService.emit(CommandEventContext.create(COMMAND_ATTACHED));
    }
    clientsByPaths.computeIfAbsent(path, k -> ConcurrentHashMap.newKeySet()).add(conn);
    logger.debug("New connection to {} on path {}", conn.getRemoteSocketAddress(), path);

    welcomeClient(conn, path);
  }

  @Override
  public void onClose(WebSocket conn, int code, String reason, boolean remote) {
    for (Set<WebSocket> clients : clientsByPaths.values()) {
      if (clients.remove(conn)) {
        break;
      }
    }
    logger.debug(
        "closed {} with exit code {} additional info: {}",
        conn.getRemoteSocketAddress(),
        code,
        reason);
  }

  /**
   * Can be manually tested with a websocket client cli like websocat. Replace logger with the class
   * you want to update: `$ echo
   * "{\"command\":\"logging/update\",\"data\":{\"loggers\":[{\"logger\":\"your.logger.class\",\"level\":\"DEBUG\",\"group\":false}]}}"
   * | websocat ws://localhost:54953/cap-console/logs -1 -u -t
   */
  @Override
  public void onMessage(WebSocket conn, String message) {
    logger.debug("received message from {}: {}", conn.getRemoteSocketAddress(), message);
    try {
      CommandEvent commandEvent = objectMapper.readValue(message, CommandEvent.class);
      remoteMonitoringService.emit(commandEvent);
      logger.debug("Emitted CommandEvent from websocket: {}", commandEvent.getCommand());
    } catch (Exception e) {
      logger.warn("Failed to parse or emit CommandEvent: {}", e.getMessage(), e);
    }
  }

  @Override
  public void onMessage(WebSocket conn, ByteBuffer message) {
    logger.debug("received ByteBuffer from {}", conn.getRemoteSocketAddress());
  }

  @Override
  public void onError(WebSocket conn, Exception ex) {
    if (conn != null) {
      logger.error("an error occurred on connection {}:", conn, ex);
    } else {
      logger.error("an error occurred on server: ", ex);
    }
    if (error != null) {
      error.set(ex);
    }
    if (startupLatch != null) {
      startupLatch.countDown();
    }
  }

  @Override
  public void onStart() {
    logger.info("Started CAP console remote-monitoring server on port {}", getPort());
    if (startupLatch != null) {
      startupLatch.countDown();
    }
  }

  private void welcomeClient(WebSocket conn, String path) {
    RemoteLogData welcomeMsg = new RemoteLogData.Builder()
        .level("INFO")
        .logger("system")
        .thread(Thread.currentThread().getName())
        .type("welcome")
        .message("Welcome to CAP console Remote Monitoring.")
        .build();

    InfoEvent infoEvent = InfoEvent.createRemoteLog(path, welcomeMsg);
    conn.send(infoEvent.toJson());
  }
}
