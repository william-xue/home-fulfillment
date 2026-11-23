package com.homefulfillment.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.homefulfillment.catalog.OrderItems.Input;
import com.homefulfillment.identity.Identity;
import com.homefulfillment.web.Page;
import com.homefulfillment.web.RequestId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
class InboundController {
    private final InboundRead read;
    private final InboundCommands commands;
    private final Identity identity;
    InboundController(InboundRead read, InboundCommands commands, Identity identity) {
        this.read = read; this.commands = commands; this.identity = identity;
    }

    record Receive(@NotNull @Min(1) Integer lineNo, @NotNull @Pattern(regexp="[A-Z0-9-]{1,64}") String binCode,
                   @NotNull @Min(1) @Max(1000000) Integer quantity) {}
    record PutAway(@NotNull @Pattern(regexp="[A-Z0-9-]{1,64}") String destinationBinCode,
                   @NotNull @Min(1) @Max(1000000) Integer quantity) {}
    record NewPurchase(@NotNull @Pattern(regexp="[A-Z0-9-]{1,64}") String warehouseCode,
                       @NotBlank @Size(max=120) String supplierName,
                       @NotNull @Size(min=1,max=100) List<@NotNull @Valid Input> lines) {}

    @PostMapping("/purchase-orders")
    ResponseEntity<JsonNode> create(@Valid @RequestBody NewPurchase body, @RequestHeader("Idempotency-Key") String key,
                                    Authentication auth, HttpServletRequest request) {
        return commands.createPurchase(identity.actor(auth),body.warehouseCode(),body.supplierName().strip(),
                body.lines(),key,RequestId.value(request));
    }

    @GetMapping("/purchase-orders")
    Page<InboundRead.PurchaseSummary> list(Authentication auth,
            @RequestParam(defaultValue="") String warehouseCode, @RequestParam(defaultValue="") String status,
            @RequestParam(defaultValue="") String poNo, @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size) {
        return read.purchases(identity.actor(auth),warehouseCode,status,poNo,Page.Window.of(page,size));
    }

    @GetMapping("/purchase-orders/{poNo}")
    InboundRead.PurchaseDetail purchase(@PathVariable String poNo, Authentication auth) {
        return read.purchaseDetail(poNo,identity.actor(auth));
    }

    @PostMapping("/purchase-orders/{poNo}/receipts")
    ResponseEntity<JsonNode> receive(@PathVariable String poNo, @Valid @RequestBody Receive body,
            @RequestHeader("Idempotency-Key") String key, Authentication auth, HttpServletRequest request) {
        return commands.receive(identity.actor(auth),poNo,body.lineNo(),body.binCode(),body.quantity(),key,RequestId.value(request));
    }

    @GetMapping("/receipts/{receiptNo}")
    InboundRead.ReceiptDetail receipt(@PathVariable String receiptNo, Authentication auth) {
        return read.receiptDetail(receiptNo,identity.actor(auth));
    }

    @PostMapping("/receipts/{receiptNo}/put-aways")
    ResponseEntity<JsonNode> putAway(@PathVariable String receiptNo, @Valid @RequestBody PutAway body,
            @RequestHeader("Idempotency-Key") String key, Authentication auth, HttpServletRequest request) {
        return commands.putAway(identity.actor(auth),receiptNo,body.destinationBinCode(),body.quantity(),key,RequestId.value(request));
    }
}
