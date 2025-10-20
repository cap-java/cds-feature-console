package com.sap.cds.feature.console.service;

import com.sap.cds.feature.console.connectivity.RemoteMonitoringHandler;
import com.sap.cds.feature.console.info.collectors.LogCollector;
import com.sap.cds.services.runtime.CdsRuntimeConfiguration;
import com.sap.cds.services.runtime.CdsRuntimeConfigurer;

public class RemoteMonitoringConfiguration implements CdsRuntimeConfiguration {

  private static final String TYPE = "console";
  public static final String COMMAND_ATTACHED = TYPE + "/attached";
  private RemoteMonitoringService remoteMonitoringService;

  @Override
  public int order() {
    return Integer.MAX_VALUE;
  }

  @Override
  public void services(CdsRuntimeConfigurer configurer) {
    remoteMonitoringService = new RemoteMonitoringServiceImpl();
    remoteMonitoringService.startRemoteMonitoringServer();
    configurer.service(remoteMonitoringService);
  }

  @Override
  public void eventHandlers(CdsRuntimeConfigurer configurer) {
    if (remoteMonitoringService != null) {
      configurer.eventHandler(new RemoteMonitoringHandler(remoteMonitoringService.getRemoteMonitoringServer()));
      configurer.eventHandler(new LogCollector(configurer.getCdsRuntime(), remoteMonitoringService));
    }
  }

}
