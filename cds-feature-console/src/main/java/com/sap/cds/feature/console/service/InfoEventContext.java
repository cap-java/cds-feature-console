package com.sap.cds.feature.console.service;

import com.sap.cds.services.EventContext;
import com.sap.cds.services.EventName;

@EventName(RemoteMonitoringService.EVENT_INFO)
public interface InfoEventContext extends EventContext {

  static InfoEventContext create(InfoEvent event) {
    InfoEventContext context =
        EventContext.create(RemoteMonitoringService.EVENT_INFO, null).as(InfoEventContext.class);
    context.setInfoEvent(event);
    return context;
  }

  void setInfoEvent(InfoEvent event);

  InfoEvent getInfoEvent();

}
