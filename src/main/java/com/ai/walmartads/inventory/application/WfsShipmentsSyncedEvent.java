package com.ai.walmartads.inventory.application;

/** An active shipment snapshot was committed; refresh only the originating workspace. */
public record WfsShipmentsSyncedEvent(long workspaceId) { }
