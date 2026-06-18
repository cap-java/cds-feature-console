package com.sap.cds.feature.console.service;

import com.sap.cds.services.environment.CdsProperties.Outbox.OutboxServiceConfig;
import java.util.Map;

/**
 * Class representing outbox configuration data for serialization to the client. Extracts only
 * serializable properties from OutboxServiceConfig.
 */
public final class OutboxConfig {
  private final String name;
  private final int maxAttempts;
  private final boolean ordered;
  private final boolean enabled;

  private OutboxConfig(String name, int maxAttempts, boolean ordered, boolean enabled) {
    this.name = name;
    this.maxAttempts = maxAttempts;
    this.ordered = ordered;
    this.enabled = enabled;
  }

  /**
   * Creates an OutboxConfig from a CAP OutboxServiceConfig and outbox name.
   *
   * @param config the OutboxServiceConfig from CAP framework
   * @param name the name of the outbox service
   * @return OutboxConfig instance
   */
  public static OutboxConfig fromServiceConfig(OutboxServiceConfig config, String name) {
    return new OutboxConfig(name, config.getMaxAttempts(), config.isOrdered(), config.isEnabled());
  }

  public Map<String, Object> toMap() {
    return Map.of("name", name, "maxAttempts", maxAttempts, "ordered", ordered, "enabled", enabled);
  }
}
