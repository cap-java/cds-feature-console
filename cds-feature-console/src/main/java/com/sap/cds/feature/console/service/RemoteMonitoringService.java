package com.sap.cds.feature.console.service;

import com.sap.cds.feature.console.connectivity.RemoteMonitoringServer;
import com.sap.cds.services.Service;

public interface RemoteMonitoringService extends Service {

  String EVENT_INFO = "consoleRemoteMonitoringInfoEvent";
  String DEFAULT_NAME = "RemoteMonitoringService#Default";

  void emit(InfoEvent event);

  void emit(CommandEvent command);

  RemoteMonitoringServer getRemoteMonitoringServer();

  void startRemoteMonitoringServer();

}
