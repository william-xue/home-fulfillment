package com.homefulfillment.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.homefulfillment.catalog.OrderItems.Input;
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
import java.util.List;

@RestController
@RequestMapping("/api/v1/orders")
class OrderController {
    private final Identity identity;
    private final OrderRead read;
    private final OrderCommands commands;
    OrderController(Identity identity, OrderRead read, OrderCommands commands) {
        this.identity = identity; this.read = read; this.commands = commands;
    }

    record Accept(@NotNull @Pattern(regexp="DIRECT|DOUYIN") String channel, @NotBlank @Size(max=64) String channelOrderNo,
                  @NotBlank @Size(max=40) String paidAt,@NotNull @Size(min=1,max=100) List<@NotNull @Valid Input> lines) {}
    record Cancel(@NotBlank @Size(max=200) String reason) {}

    @GetMapping
    Page<OrderRead.Summary> list(Authentication auth,@RequestParam(defaultValue="") String channel,
            @RequestParam(defaultValue="") String channelOrderNo,@RequestParam(defaultValue="") String orderNo,
            @RequestParam(defaultValue="") String routeStatus,@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size) {
        return read.list(identity.actor(auth),channel,channelOrderNo,orderNo,routeStatus,Page.Window.of(page,size));
    }

    @GetMapping("/{orderNo}")
    OrderRead.Detail detail(Authentication auth,@PathVariable String orderNo) {
        return read.detail(orderNo,identity.actor(auth));
    }

    @PostMapping
    ResponseEntity<JsonNode> accept(Authentication auth,@Valid @RequestBody Accept body,
            @RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        var actor = identity.actor(auth);
        actor.requireOps();
        Instant paidAt;
        try { paidAt = Instant.parse(body.paidAt()); }
        catch (DateTimeParseException ex) { throw ApiProblem.invalid("支付时间须含时区，例如 2026-01-01T00:00:00Z"); }
        return commands.accept(actor,body.channel(),body.channelOrderNo().strip(),paidAt,body.lines(),key,RequestId.value(request));
    }

    @PostMapping("/{orderNo}/route")
    ResponseEntity<JsonNode> route(Authentication auth,@PathVariable String orderNo,
            @RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        return commands.route(identity.actor(auth),orderNo,key,RequestId.value(request));
    }

    @PostMapping("/{orderNo}/cancel")
    ResponseEntity<JsonNode> cancel(Authentication auth,@PathVariable String orderNo,@Valid @RequestBody Cancel body,
            @RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        return commands.cancel(identity.actor(auth),orderNo,body.reason().strip(),key,RequestId.value(request));
    }
}
