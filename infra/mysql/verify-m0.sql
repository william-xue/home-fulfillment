SELECT DATABASE() AS active_schema;

SELECT version, description, success
FROM flyway_schema_history
ORDER BY installed_rank;

SELECT (SELECT COUNT(*) FROM sku WHERE code = 'HOME-BOX-01') AS sample_skus,
       (SELECT COUNT(*) FROM warehouse WHERE code IN ('SH', 'GZ')) AS sample_warehouses,
       COUNT(*) AS sample_bins
FROM bin b
JOIN warehouse w ON w.id = b.warehouse_id
WHERE w.code IN ('SH', 'GZ') AND b.code IN ('RECV-01', 'A-01-01');
