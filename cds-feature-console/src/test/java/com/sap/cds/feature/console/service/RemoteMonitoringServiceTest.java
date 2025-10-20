package com.sap.cds.feature.console.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;

import org.junit.jupiter.api.Test;

class RemoteMonitoringServiceTest {

  private static final int PORT = 54953;

  @Test
  void startRemoteMonitoringServerWhenPortInUseFallsBackToRandomPort() throws Exception {
    RemoteMonitoringService remoteMonitoringService01 = new RemoteMonitoringServiceImpl();
    RemoteMonitoringService remoteMonitoringService02 = new RemoteMonitoringServiceImpl();
    try {
      remoteMonitoringService01.startRemoteMonitoringServer();

      remoteMonitoringService02.startRemoteMonitoringServer();

      int actualPort = remoteMonitoringService02.getRemoteMonitoringServer().getPort();
      assertThat(actualPort, not(PORT));
      assertThat(actualPort, greaterThan(0));
    } finally {
      remoteMonitoringService01.getRemoteMonitoringServer().stop();
      remoteMonitoringService02.getRemoteMonitoringServer().stop();
    }
  }

}
