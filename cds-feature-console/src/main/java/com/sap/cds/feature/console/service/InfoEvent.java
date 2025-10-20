package com.sap.cds.feature.console.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sap.cds.CdsData;
import com.sap.cds.Struct;
import java.util.HashMap;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public interface InfoEvent extends CdsData {

  String getPath();

  void setPath(String path);

  Map<String, Object> getData();

  void setData(Map<String, Object> data);

  static InfoEvent create() {
    InfoEvent event = Struct.create(InfoEvent.class);
    event.put("path", "unknown");
    event.setData(new HashMap<>());
    return event;
  }

  static InfoEvent create(String path) {
    InfoEvent event = Struct.create(InfoEvent.class);
    event.put("path", path);
    event.setData(new HashMap<>());
    return event;
  }

  static InfoEvent create(String path, Map<String, Object> data) {
    InfoEvent event = Struct.create(InfoEvent.class);
    event.put("path", path);
    event.setData(data);
    return event;
  }

  /**
   * Creates a structured log event using RemoteLogData.
   *
   * @param path the event path
   * @param logData structured log data
   * @return InfoEvent with properly structured data
   */
  static InfoEvent createRemoteLog(String path, RemoteLogData logData) {
    InfoEvent event = Struct.create(InfoEvent.class);
    event.setPath(path);
    event.setData(logData.toMap());
    return event;
  }
}
