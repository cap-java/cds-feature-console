package com.sap.cds.feature.console.service;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.util.Map;

public class CommandEvent {

  private String command;
  private Map<String, Object> data;

  public CommandEvent() {

  }

  public CommandEvent(String command, Map<String, Object> data) {
    super();
    this.command = command;
    this.data = data;
  }

  public String getCommand() {
    return command;
  }

  @JsonAnySetter
  public void setData(Map<String, Object> data) {
    this.data = data;
  }

  public Map<String, Object> getData() {
    return data;
  }

}
