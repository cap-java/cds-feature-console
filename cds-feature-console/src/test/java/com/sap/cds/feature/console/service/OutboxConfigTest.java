package com.sap.cds.feature.console.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sap.cds.services.environment.CdsProperties.Outbox.OutboxServiceConfig;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class OutboxConfigTest {

  static Stream<Arguments> outboxConfigs() {
    return Stream.of(
        Arguments.of("my-outbox", 5, true, true),
        Arguments.of("disabled-outbox", 3, false, false),
        Arguments.of("test", 1, false, true));
  }

  @ParameterizedTest
  @MethodSource("outboxConfigs")
  void fromServiceConfigMapsAllProperties(
      String name, int maxAttempts, boolean ordered, boolean enabled) {
    OutboxServiceConfig config = mock(OutboxServiceConfig.class);
    when(config.getMaxAttempts()).thenReturn(maxAttempts);
    when(config.isOrdered()).thenReturn(ordered);
    when(config.isEnabled()).thenReturn(enabled);

    OutboxConfig outboxConfig = OutboxConfig.fromServiceConfig(config, name);

    Map<String, Object> map = outboxConfig.toMap();
    assertEquals(4, map.size());
    assertEquals(name, map.get("name"));
    assertEquals(maxAttempts, map.get("maxAttempts"));
    assertEquals(ordered, map.get("ordered"));
    assertEquals(enabled, map.get("enabled"));
  }
}
