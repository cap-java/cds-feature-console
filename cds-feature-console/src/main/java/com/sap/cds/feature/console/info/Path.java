package com.sap.cds.feature.console.info;

public final class Path {

  private Path() {

  }

  public static final String CONSOLE = "console";
  public static final String REMOTE_MONITORING = "remote-monitoring";
  public static final String CONSOLE_NOTIFICATION =
      CONSOLE + "." + REMOTE_MONITORING + ".notification";

  public static final String SYSTEM = "system";

  public static final String CDS = "cds";
  public static final String CDS_ENTITY = CDS + ".entity";

  public static final String TRACES = "traces";
  public static final String TRACES_OUTPUT = TRACES + ".output";
  public static final String TRACES_EVENTS = TRACES + ".events";

}
