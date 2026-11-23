package com.homefulfillment.operations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
class OperationsSummary {
    // No inventory, permission, or transaction state is read from this key.
    private static final String KEY="hf:operations:summary:v1";
    private static final Duration TTL=Duration.ofSeconds(30);
    private final JdbcClient db;
    private final StringRedisTemplate cache;
    private final ObjectMapper json;
    private final boolean enabled;

    OperationsSummary(JdbcClient db,StringRedisTemplate cache,ObjectMapper json,@Value("${hf.operations-cache}") boolean enabled) {
        this.db=db; this.cache=cache; this.json=json; this.enabled=enabled;
    }

    record Result(long shortageCount,long unshippedFulfillmentCount,long failedEventCount,Instant asOf) {}

    Result summary() {
        if (!enabled) return readMysql();
        try {
            String previous=cache.opsForValue().get(KEY);
            if (previous!=null) return json.readValue(previous,Result.class);
        } catch (DataAccessException | JsonProcessingException unavailable) {
            return readMysql();
        }
        Result current=readMysql();
        try {
            cache.opsForValue().set(KEY,json.valueToTree(current).toString(),TTL);
        } catch (DataAccessException unavailable) {
            // The SQL result is already available.
        }
        return current;
    }

    private Result readMysql() {
        return db.sql("""
                SELECT (SELECT COUNT(*) FROM channel_order WHERE route_status='SHORTAGE'),
                       (SELECT COUNT(*) FROM fulfillment_order WHERE status NOT IN ('DISPATCHED','CANCELLED')),
                       (SELECT COUNT(*) FROM outbox_event WHERE status='FAILED'),CURRENT_TIMESTAMP(3)
                """).query((r,n)->new Result(r.getLong(1),r.getLong(2),r.getLong(3),r.getTimestamp(4).toInstant())).single();
    }
}
