package com.homefulfillment.operations;

import com.fasterxml.jackson.databind.JsonNode;
import com.homefulfillment.events.OutboxStore;
import com.homefulfillment.identity.Identity;
import com.homefulfillment.web.CommandJournal;
import com.homefulfillment.web.Page;
import com.homefulfillment.web.RequestId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/operations")
class OperationsController {
    private final Identity identity;
    private final OperationsSummary summary;
    private final OutboxStore outbox;
    private final CommandJournal journal;
    OperationsController(Identity identity,OperationsSummary summary,OutboxStore outbox,CommandJournal journal) {
        this.identity=identity; this.summary=summary; this.outbox=outbox; this.journal=journal;
    }

    @GetMapping("/summary")
    OperationsSummary.Result summary(Authentication auth) {
        identity.actor(auth).requireOps();
        return summary.summary();
    }

    @GetMapping("/outbox")
    Page<OutboxStore.View> list(Authentication auth,@RequestParam(defaultValue="") String status,
            @RequestParam(defaultValue="") String dispatchNo,@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size) {
        identity.actor(auth).requireOps();
        return outbox.list(status,dispatchNo,Page.Window.of(page,size));
    }

    @PostMapping("/outbox/{eventId}/retry")
    ResponseEntity<JsonNode> retry(Authentication auth,@PathVariable String eventId,
            @RequestHeader("Idempotency-Key") String key,HttpServletRequest request) {
        var actor=identity.actor(auth);
        actor.requireOps();
        return journal.execute(actor.id(),"RETRY_OUTBOX",key,Map.of("path","/operations/outbox/"+eventId+"/retry"),
                ()->ResponseEntity.ok(outbox.retry(eventId,actor.id(),RequestId.value(request))));
    }
}
