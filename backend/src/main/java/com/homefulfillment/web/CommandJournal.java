package com.homefulfillment.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

@Service
public class CommandJournal {
    private final JdbcClient db;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public CommandJournal(JdbcClient db, ObjectMapper json, PlatformTransactionManager manager) {
        this.db = db;
        this.json = json;
        this.transactions = new TransactionTemplate(manager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private record Entry(String hash, int status, String body) {}
    private static class AlreadyClaimed extends RuntimeException {}

    // The caller must authorize against the current resource before invoking this method.
    public ResponseEntity<JsonNode> create(long actor, String operation, String key, Map<String, ?> request,
                                           Supplier<?> apply) {
        return execute(actor,operation,key,request,() -> ResponseEntity.status(201).body(apply.get()));
    }

    public ResponseEntity<JsonNode> execute(long actor, String operation, String key, Map<String, ?> request,
                                           Supplier<ResponseEntity<?>> apply) {
        if (key == null || !key.matches("[A-Za-z0-9._-]{1,64}")) throw ApiProblem.invalid("缺少或无效的请求键");
        String fingerprint = fingerprint(request);
        for (int attempt=1; attempt<=3; attempt++) {
            try {
                return transactions.execute(tx -> {
                    try {
                        db.sql("INSERT INTO command_receipt(actor_id,operation,idempotency_key,request_hash) VALUES(?,?,?,?)")
                                .params(actor,operation,key,fingerprint).update();
                    } catch (DuplicateKeyException ex) {
                        throw new AlreadyClaimed();
                    }
                    ResponseEntity<?> result = apply.get();
                    int status = result.getStatusCode().value();
                    JsonNode body = json.valueToTree(result.getBody());
                    if (db.sql("""
                            UPDATE command_receipt SET http_status=?,result_json=?
                            WHERE actor_id=? AND operation=? AND idempotency_key=?
                            """).params(status,body.toString(),actor,operation,key).update()!=1) {
                        throw new IllegalStateException("Command evidence not persisted");
                    }
                    return ResponseEntity.status(status).body(body);
                });
            } catch (AlreadyClaimed ex) {
                Entry entry = db.sql("""
                        SELECT request_hash,http_status,result_json FROM command_receipt
                        WHERE actor_id=? AND operation=? AND idempotency_key=?
                        """).params(actor,operation,key).query((r,n)->new Entry(r.getString(1),r.getInt(2),r.getString(3))).single();
                if (!entry.hash().equals(fingerprint)) {
                    throw ApiProblem.conflict("IDEMPOTENCY_CONFLICT","此请求键已用于不同的业务内容");
                }
                try {
                    return ResponseEntity.status(entry.status()).body(json.readTree(entry.body()));
                } catch (com.fasterxml.jackson.core.JsonProcessingException ex2) {
                    throw new IllegalStateException("Invalid stored command result",ex2);
                }
            } catch (PessimisticLockingFailureException ex) {
                if (attempt == 3) throw new ApiProblem(503,"WRITE_BUSY","写入繁忙；请用原请求键重试");
            }
        }
        throw new IllegalStateException("Unexpected command retry exit");
    }

    public String fingerprint(Map<String, ?> request) {
        return hash(json.valueToTree(new TreeMap<>(request)).toString());
    }

    private static String hash(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
