package com.sap.cds.feature.console.service;

import java.util.Map;

/**
 * Class representing remote log data that matches the client ZOD schema.
 * This ensures type safety and prevents parsing errors on the client side.
 */
public final class RemoteLogData {
  private final String level;
  private final String logger;
  private final String thread;
  // event type (e.g., "log", "exception")
  private final String type;
  // log message (String or array) - for exceptions, contains the exception details
  private final Object message;
  // timestamp in milliseconds
  private final long ts;

  private RemoteLogData(Builder builder) {
    this.level = builder.level;
    this.logger = builder.logger;
    this.thread = builder.thread;
    this.type = builder.type;
    this.message = builder.message;
    this.ts = builder.ts;
  }

  public static class Builder {
    private String level;
    private String logger;
    private String thread;
    private String type;
    private Object message;
    private long ts;

    public Builder level(String level) {
      this.level = level;

      return this;
    }

    public Builder logger(String logger) {
      this.logger = logger;

      return this;
    }

    public Builder thread(String thread) {
      this.thread = thread;

      return this;
    }

    public Builder type(String type) {
      this.type = type;

      return this;
    }

    public Builder message(Object message) {
      this.message = message;
      return this;
    }

    public Builder ts(long ts) {
      this.ts = ts;

      return this;
    }

    public RemoteLogData build() {
      // sensible defaults for null or empty fields.
      level = isNullOrEmpty(level) ? "INFO" : level.toUpperCase();
      logger = isNullOrEmpty(logger) ? "unknown" : logger;
      thread = isNullOrEmpty(thread) ? "main" : thread;
      type = isNullOrEmpty(type) ? "log" : type;
      message = (message == null) ? "" : message;
      ts = ts == 0 ? System.currentTimeMillis() : ts;

      return new RemoteLogData(this);
    }

    public static boolean isNullOrEmpty(String str) {
      return str == null || str.trim().isEmpty();
    }
  }

  public Map<String, Object> toMap() {
    return Map.of(
        "level", level,
        "logger", logger,
        "thread", thread,
        "type", type,
        "message", message,
        "ts", ts
    );
  }
}
