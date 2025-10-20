package com.sap.cds.feature.console.connectivity;

import com.sap.cds.feature.console.service.CommandEventContext;
import com.sap.cds.feature.console.service.InfoEventContext;
import com.sap.cds.feature.console.service.RemoteMonitoringService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.HandlerOrder;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.utils.OrderConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ServiceName(RemoteMonitoringService.DEFAULT_NAME)
public class RemoteMonitoringHandler implements EventHandler {

  private static final Logger logger = LoggerFactory.getLogger(RemoteMonitoringHandler.class);
  private final RemoteMonitoringServer remoteMonitoringServer;

  public RemoteMonitoringHandler(RemoteMonitoringServer server) {
    this.remoteMonitoringServer = server;
  }

  @On
  private void handleInfoEvent(InfoEventContext context) {
    logger.debug("Handling info '{}'", context.getEvent());
    this.remoteMonitoringServer.broadcastToPath(
        context.getInfoEvent().toJson(), RemoteMonitoringServer.PATH_LOGS);

    context.setCompleted();
  }

  @On
  @HandlerOrder(OrderConstants.On.AUTO_COMPLETE)
  private void handleDashboardCommandEvent(CommandEventContext context) {
    logger.debug("Handling command '{}'", context.getEvent());
    context.setCompleted();
  }

}
