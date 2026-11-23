package com.homefulfillment.catalog;

import com.homefulfillment.web.ApiProblem;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.List;

/** Resolves SKU codes for both kinds of incoming orders. No stock is touched here. */
@Repository
public class OrderItems {
    private final JdbcClient db;
    OrderItems(JdbcClient db) { this.db = db; }

    public record Input(@NotNull @Pattern(regexp="[A-Z0-9-]{1,64}") String skuCode,
                        @NotNull @Min(1) @Max(1000000) Integer quantity) {}
    public record Item(long skuId, int quantity) {}

    public List<Item> resolve(List<Input> lines) {
        var seen = new HashSet<String>();
        return lines.stream().map(line -> {
            if (!seen.add(line.skuCode())) throw ApiProblem.invalid("同一 SKU 只能出现一行：" + line.skuCode());
            long id = db.sql("SELECT id FROM sku WHERE code=?").param(line.skuCode()).query(Long.class).optional()
                    .orElseThrow(() -> ApiProblem.invalid("SKU 不存在：" + line.skuCode()));
            return new Item(id,line.quantity());
        }).toList();
    }
}
