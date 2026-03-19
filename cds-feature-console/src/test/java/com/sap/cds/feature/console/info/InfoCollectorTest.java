package com.sap.cds.feature.console.info;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class InfoCollectorTest {

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
    assertFalse(InfoCollector.isInRemoteMonitoringContext());

    assertThrows(RuntimeException.class, () ->
        InfoCollector.inRemoteMonitoringContext(() -> {
          throw new RuntimeException("test error");
        }));

    assertFalse(InfoCollector.isInRemoteMonitoringContext(),
        "Flag should be reset even after exception");
  }
}
