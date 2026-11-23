INSERT INTO sku (code, name)
SELECT 'HOME-BOX-01', '家居收纳盒'
WHERE NOT EXISTS (SELECT 1 FROM sku WHERE code = 'HOME-BOX-01');

INSERT INTO warehouse (code, name, route_priority)
SELECT 'SH', '上海仓', 10
WHERE NOT EXISTS (SELECT 1 FROM warehouse WHERE code = 'SH');

INSERT INTO warehouse (code, name, route_priority)
SELECT 'GZ', '广州仓', 20
WHERE NOT EXISTS (SELECT 1 FROM warehouse WHERE code = 'GZ');

INSERT INTO bin (warehouse_id, code, type, pick_priority)
SELECT w.id, 'RECV-01', 'RECEIVING', 0 FROM warehouse w
WHERE w.code IN ('SH', 'GZ')
  AND NOT EXISTS (SELECT 1 FROM bin b WHERE b.warehouse_id = w.id AND b.code = 'RECV-01');

INSERT INTO bin (warehouse_id, code, type, pick_priority)
SELECT w.id, 'A-01-01', 'PICKABLE', 100 FROM warehouse w
WHERE w.code IN ('SH', 'GZ')
  AND NOT EXISTS (SELECT 1 FROM bin b WHERE b.warehouse_id = w.id AND b.code = 'A-01-01');
