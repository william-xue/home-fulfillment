package com.homefulfillment.tms;

import com.fasterxml.jackson.databind.JsonNode;
import com.homefulfillment.identity.Identity;
import com.homefulfillment.web.ApiProblem;
import com.homefulfillment.web.Page;
import com.homefulfillment.web.RequestId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.format.DateTimeParseException;

@RestController
@RequestMapping("/api/v1")
class ShipmentController {
    private final Identity identity;
    private final Shipments shipments;
    ShipmentController(Identity identity,Shipments shipments) { this.identity=identity; this.shipments=shipments; }

    record Tracking(@NotBlank @Size(max=64) String sourceEventNo,@NotNull @Pattern(regexp="IN_TRANSIT|DELIVERED") String type,
                    @NotBlank @Size(max=40) String occurredAt) {}

    @PostMapping("/fulfillments/{fulfillmentNo}/shipment")
    ResponseEntity<JsonNode> create(Authentication auth,@PathVariable String fulfillmentNo,
            @RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        return shipments.create(identity.actor(auth),fulfillmentNo,key,RequestId.value(request));
    }

    @GetMapping("/shipments")
    Page<Shipments.Summary> list(Authentication auth,@RequestParam(defaultValue="") String warehouseCode,
            @RequestParam(defaultValue="") String status,@RequestParam(defaultValue="") String fulfillmentNo,
            @RequestParam(defaultValue="") String shipmentNo,@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size) {
        return shipments.list(identity.actor(auth),warehouseCode,status,fulfillmentNo,shipmentNo,Page.Window.of(page,size));
    }

    @GetMapping("/shipments/{shipmentNo}")
    Shipments.Detail detail(Authentication auth,@PathVariable String shipmentNo) {
        return shipments.detail(shipmentNo,identity.actor(auth));
    }

    @PostMapping("/shipments/{shipmentNo}/events")
    ResponseEntity<JsonNode> track(Authentication auth,@PathVariable String shipmentNo,@Valid @RequestBody Tracking body,
            @RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        var actor=identity.actor(auth);
        actor.requireOps();
        Instant occurred;
        try { occurred=Instant.parse(body.occurredAt()); }
        catch (DateTimeParseException ex) { throw ApiProblem.invalid("轨迹时间须含时区"); }
        if (occurred.isBefore(Instant.parse("1000-01-01T00:00:00Z")) || !occurred.isBefore(Instant.parse("+10000-01-01T00:00:00Z"))) {
            throw ApiProblem.invalid("轨迹时间不合法");
        }
        return shipments.addEvent(actor,shipmentNo,body.sourceEventNo().strip(),body.type(),occurred,key,RequestId.value(request));
    }
}
