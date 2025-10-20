package com.sap.cds.feature.console.service;

import com.sap.cds.services.EventContext;
import com.sap.cds.services.EventName;
import java.util.Map;

@EventName("*")
public interface CommandEventContext extends EventContext {

  /**
   * Creates a new {@link CommandEventContext}.
   *
   * @param command command
   * @return the {@link CommandEventContext}
   */
  static CommandEventContext create(String command) {
    return EventContext.create(command, null).as(CommandEventContext.class);
  }

  void setData(Map<String, Object> data);

  Map<String, Object> getData();

}
