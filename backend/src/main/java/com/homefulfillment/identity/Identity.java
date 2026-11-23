package com.homefulfillment.identity;

import com.homefulfillment.web.ApiProblem;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class Identity {
    private final JdbcClient db;

    public Identity(JdbcClient db) { this.db = db; }

    record Account(long id, String username, String hash, String role, boolean enabled) {}

    Account account(String username) {
        return db.sql("SELECT id,username,password_hash,role,enabled FROM app_user WHERE username=?")
                .param(username).query((r,n) -> new Account(r.getLong(1),r.getString(2),r.getString(3),
                        r.getString(4),r.getBoolean(5))).optional().orElse(null);
    }

    public Actor actor(Authentication auth) {
        Account account = account(auth.getName());
        if (account == null || !account.enabled()) {
            throw new ApiProblem(401, "UNAUTHENTICATED", "请重新登录");
        }
        List<String> warehouses = switch (account.role()) {
            case "WAREHOUSE" -> db.sql("""
                SELECT w.code FROM user_warehouse u JOIN warehouse w ON w.id=u.warehouse_id
                WHERE u.user_id=? ORDER BY w.route_priority
                """).param(account.id()).query(String.class).list();
            case "OPS" -> db.sql("SELECT code FROM warehouse ORDER BY route_priority").query(String.class).list();
            default -> List.of();
        };
        return new Actor(account.id(), account.username(), account.role(), warehouses);
    }

    public record Actor(long id, String username, String role, List<String> warehouses) {
        public boolean canWork() { return "WAREHOUSE".equals(role); }
        public boolean isOps() { return "OPS".equals(role); }
        public void requireOps() { if (!isOps()) throw ApiProblem.forbidden(); }
        public void requireOrdersRead() {
            if (!isOps() && !"SUPPORT".equals(role)) throw ApiProblem.forbidden();
        }
        public void requireInboundRead() {
            if (!canWork() && !"OPS".equals(role)) throw ApiProblem.forbidden();
        }
        public void requireWarehouse(String warehouse, boolean write) {
            requireInboundRead();
            if (write && !canWork() || !warehouses.contains(warehouse)) throw ApiProblem.forbidden();
        }
    }
}
