package com.homefulfillment.events;

import java.time.Instant;

public record DispatchEvent(String eventId,String eventType,Instant occurredAt,String dispatchNo,
                            String fulfillmentNo,String warehouseCode) {
    public static final String TYPE="FulfillmentDispatched.v1";
    public static final String TOPIC="hf-fulfillment-dispatched-v1";
}
