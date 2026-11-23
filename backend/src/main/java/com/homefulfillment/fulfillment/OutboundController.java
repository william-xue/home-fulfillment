package com.homefulfillment.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.homefulfillment.identity.Identity;
import com.homefulfillment.web.Page;
import com.homefulfillment.web.RequestId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/fulfillments")
class OutboundController {
    private final Identity identity;
    private final OutboundRead read;
    private final OutboundCommands commands;
    OutboundController(Identity identity,OutboundRead read,OutboundCommands commands) {
        this.identity=identity; this.read=read; this.commands=commands;
    }

    @GetMapping
    Page<OutboundRead.Summary> list(Authentication auth,@RequestParam(defaultValue="") String warehouseCode,
            @RequestParam(defaultValue="") String status,@RequestParam(defaultValue="") String orderNo,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        return read.list(identity.actor(auth),warehouseCode,status,orderNo,Page.Window.of(page,size));
    }

    @GetMapping("/{number}")
    OutboundRead.Detail detail(Authentication auth,@PathVariable String number) {
        return read.detail(number,identity.actor(auth));
    }

    @PostMapping("/{number}/tasks/{task}/pick")
    ResponseEntity<JsonNode> pick(Authentication auth,@PathVariable String number,@PathVariable String task,
            @RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        return commands.pick(identity.actor(auth),number,task,key,RequestId.value(request));
    }

    @PostMapping("/{number}/dispatch")
    ResponseEntity<JsonNode> dispatch(Authentication auth,@PathVariable String number,
            @RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        return commands.dispatch(identity.actor(auth),number,key,RequestId.value(request));
    }
}
