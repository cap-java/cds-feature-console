package com.sap.cds.feature.console.service;

import com.sap.cds.feature.console.connectivity.RemoteMonitoringServer;
import com.sap.cds.services.ServiceDelegator;
import com.sap.cds.services.application.ApplicationLifecycleService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.LoggerFactory;

public class RemoteMonitoringServiceImpl extends ServiceDelegator
    implements RemoteMonitoringService {

  private static final org.slf4j.Logger logger =
      LoggerFactory.getLogger(RemoteMonitoringServiceImpl.class);

  private static final int PREFERRED_PORT = 54953;
  private static final int MAX_RANDOM_RETRIES = 10;
  private RemoteMonitoringServer remoteMonitoringServer;

  protected RemoteMonitoringServiceImpl() {
    super(RemoteMonitoringService.DEFAULT_NAME);
  }

  @Override
  public void emit(InfoEvent event) {
    InfoEventContext ctx = InfoEventContext.create(event);
    this.emit(ctx);
  }

  @Override
  public void emit(CommandEvent event) {
    CommandEventContext context = CommandEventContext.create(event.getCommand());
    context.setData(event.getData());
    this.emit(context);
  }

  @Override
  public RemoteMonitoringServer getRemoteMonitoringServer() {
    return remoteMonitoringServer;
  }

  /**
   * Attempts to start the remote monitoring server on a specific port. If unsuccessful, will retry
   * on random ports. Adds a shutdown hook to stop the server.
   */
  @Override
  public void startRemoteMonitoringServer() {
    if (remoteMonitoringServer != null) {
      logger.warn("RemoteMonitoringServer already started");
      return;
    }

    StartupResult result = startWithPreferredPort(PREFERRED_PORT);
    while (result.startupError instanceof java.net.BindException) {
      result = startWithRandomPortRetries(MAX_RANDOM_RETRIES);
    }

    if (result.server != null) {
      remoteMonitoringServer = result.server;
    } else {
      logger.error(
          "Failed to start RemoteMonitoringServer: {}",
          result.startupError != null ? result.startupError.getMessage() : "Unknown error",
          result.startupError);
      return;
    }

    addShutdownHook();
  }

  private StartupResult startWithPreferredPort(int preferredPort) {
    return tryStartRemoteMonitoringServer(preferredPort);
  }

  private StartupResult startWithRandomPortRetries(int maxRandomRetries) {
    StartupResult result =
        new StartupResult(null, new RuntimeException("Starting with random port failed."));
    for (int i = 0; i < maxRandomRetries; i++) {
      logger.warn("Port in use, retrying... (attempt {}/{})", i + 1, maxRandomRetries);
      result = tryStartRemoteMonitoringServer(0);
      if (result.server != null && result.startupError == null) {
        return result;
      }
      if (!(result.startupError instanceof java.net.BindException)) {
        break;
      }
    }
    return result;
  }

  /**
   * Adds a shutdown hook to stop the server. Using the CAP {@link
   * ApplicationLifecycleService#EVENT_APPLICATION_STOPPED} event didn't work.
   */
  private void addShutdownHook() {
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  logger.debug("RemoteMonitoringServer is stopping...");
                  try {
                    remoteMonitoringServer.stop();
                    logger.info("RemoteMonitoringServer stopped");
                  } catch (InterruptedException e) {
                    logger.error(
                        "Stopping RemoteMonitoringServer interrupted: {}", e.getMessage(), e);
                    Thread.currentThread().interrupt();
                  }
                }));
  }

  /**
   * Holds the result of an attempt to start a RemoteMonitoringServer. Contains the server instance
   * if startup was successful, or the exception if it failed.
   *
   * @param server the started RemoteMonitoringServer instance, or null if startup failed
   * @param startupError the exception thrown during startup, or null if startup succeeded
   */
  private record StartupResult(RemoteMonitoringServer server, Exception startupError) {}

  /**
   * Attempts to start a RemoteMonitoringServer on the specified port. Waits for the server to
   * either start successfully or fail with an error.
   *
   * @param port the port to attempt to bind the server to (0 for random port)
   * @return a StartupResult containing the server if successful, or the error if failed
   */
  private StartupResult tryStartRemoteMonitoringServer(int port) {
    AtomicReference<Exception> startupError = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    RemoteMonitoringServer server = new RemoteMonitoringServer(port, this, latch, startupError);
    server.start();
    try {
      boolean awaited = latch.await(3, TimeUnit.SECONDS);
      if (!awaited) {
        logger.error("RemoteMonitoringServer startup timed out after 3 seconds.");
        return new StartupResult(null, new RuntimeException("Startup timed out"));
      }
    } catch (InterruptedException e) {
      logger.error("latch.await interrupted: {}", e.getMessage(), e);
      Thread.currentThread().interrupt();
      return new StartupResult(null, e);
    }
    if (startupError.get() != null) {
      return new StartupResult(null, startupError.get());
    }
    return new StartupResult(server, null);
  }
}
