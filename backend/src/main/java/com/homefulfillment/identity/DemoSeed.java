package com.homefulfillment.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "hf.demo-seed", havingValue = "true")
class DemoSeed implements ApplicationRunner {
    private final JdbcTemplate db;
    private final PasswordEncoder encoder;
    private final String password;

    DemoSeed(JdbcTemplate db, PasswordEncoder encoder, @Value("${hf.demo-password}") String password) {
        this.db = db;
        this.encoder = encoder;
        this.password = password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (password.length() < 12 || password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) {
            throw new IllegalStateException("本地种子要求 HF_DEMO_PASSWORD 至少 12 字符且不超过 72 字节");
        }
        db.execute((ConnectionCallback<Void>) connection -> {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/demo/catalog.sql"));
            return null;
        });
        user("ops", "OPS", null);
        user("warehouse-sh", "WAREHOUSE", "SH");
        user("warehouse-gz", "WAREHOUSE", "GZ");
        user("support", "SUPPORT", null);
        db.update("""
            INSERT INTO purchase_order(po_no,warehouse_id,supplier_name,status,created_by)
            SELECT 'PO-HOME-001',w.id,'本地演示供应商','OPEN',u.id
            FROM warehouse w CROSS JOIN app_user u
            WHERE w.code='SH' AND u.username='ops'
              AND NOT EXISTS(SELECT 1 FROM purchase_order WHERE po_no='PO-HOME-001')
            """);
        db.update("""
            INSERT INTO purchase_order_line(po_id,line_no,sku_id,ordered_qty)
            SELECT p.id,1,s.id,10 FROM purchase_order p CROSS JOIN sku s
            WHERE p.po_no='PO-HOME-001' AND s.code='HOME-BOX-01'
              AND NOT EXISTS(SELECT 1 FROM purchase_order_line l WHERE l.po_id=p.id AND l.line_no=1)
            """);
    }

    private void user(String username, String role, String warehouse) {
        if (Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM app_user WHERE username=?)",
                Boolean.class, username))) return;
        db.update("INSERT INTO app_user(username,password_hash,role) VALUES(?,?,?)",
                username, encoder.encode(password), role);
        if (warehouse != null) {
            db.update("""
                INSERT INTO user_warehouse(user_id,warehouse_id)
                SELECT u.id,w.id FROM app_user u CROSS JOIN warehouse w WHERE u.username=? AND w.code=?
                """, username, warehouse);
        }
    }
}
