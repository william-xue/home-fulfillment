"""M2 assertions at the HTTP and MySQL boundaries (invoked by m1.py --m2)."""
from concurrent.futures import ThreadPoolExecutor
import json
import subprocess
from threading import Barrier

SKU = "HOME-BOX-01"
PAID = "2026-01-01T00:00:00Z"


def a05_browser(browser, process, await_health, web_port, root, password):
    url = f"http://127.0.0.1:{web_port}"
    with process("vite M2",["node","node_modules/vite/bin/vite.js"],root/"frontend"):
        await_health(url+"/api/health")
        def browse(*args):
            result = subprocess.run([browser,*args],cwd=root,text=True,capture_output=True,timeout=20)
            if result.returncode:
                raise AssertionError(f"M2 browser {args[0]}: {result.stderr if args[0]!='fill' else '(redacted)'}")
            return result.stdout.strip()

        def login(user, next_path):
            browse("goto",f"{url}/login?next={next_path}")
            browse("wait",'input[autocomplete="username"]')
            browse("fill",'input[autocomplete="username"]',user)
            browse("fill",'input[autocomplete="current-password"]',password)
            browse("click",'button[type="submit"]')
            browse("wait",'.topbar')

        tab = json.loads(browse("newtab",url,"--json"))["tabId"]
        try:
            login("ops","/purchase-orders/new")
            browse("wait",'select:has(option[value="GZ"])')
            browse("select",'select:has(option[value="GZ"])',"GZ")
            browse("fill",'input[maxlength="120"]',"隔离验收供应商")
            browse("wait",f'select:has(option[value="{SKU}"])')
            browse("select",f'select:has(option[value="{SKU}"])',SKU)
            browse("fill",'input[type="number"]',"4")
            browse("click",'button:has-text("创建采购单")')
            browse("wait",'text=采购明细')
            po_path = "/purchase-orders/" + browse("url").rsplit("/",1)[-1]
            assert po_path.startswith("/purchase-orders/PO-"), po_path

            login("warehouse-gz",po_path)
            browse("wait",'button:has-text("登记收货")')
            browse("click",'button:has-text("登记收货")')
            browse("wait",'select:has(option[value="RECV-01"])')
            browse("fill",'input[type="number"]',"4")
            browse("click",'button:has-text("确认收货")')
            browse("wait",'select:has(option[value="A-01-01"])')
            browse("fill",'input[type="number"]',"4")
            browse("click",'button:has-text("确认上架")')
            browse("wait",'text=查双边流水')

            login("ops","/orders/new")
            browse("wait",f'select:has(option[value="{SKU}"])')
            browse("fill",'input[placeholder="SAMPLE-001"]',"SAMPLE-001")
            local_paid = browse("js",f"""
                (() => {{
                    const paid = new Date('{PAID}');
                    return new Date(paid.getTime()-paid.getTimezoneOffset()*60000).toISOString().slice(0,16);
                }})()
                """)
            browse("fill",'input[type="datetime-local"]',local_paid)
            browse("select",f'select:has(option[value="{SKU}"])',SKU)
            browse("fill",'input[type="number"]',"10")
            browse("click",'button:has-text("确认接单")')
            browse("wait",'button:has-text("执行路由")')
            number = browse("url").rsplit("/",1)[-1]
            assert number.startswith("CO-"), number
            browse("click",'button:has-text("执行路由")')
            browse("wait",'.status-chip:text-is("已分配")')
            assert json.loads(browse("js","""
                (() => {
                    const section = [...document.querySelectorAll('section')].find(s=>s.querySelector('h2')?.textContent==='分仓履约与预占');
                    return JSON.stringify([...section.querySelectorAll('tbody tr')].map(row=>row.cells[2].textContent.trim()));
                })()
                """)) == ["8","2"]
            return number
        finally:
            subprocess.run([browser,"closetab",str(tab)],capture_output=True,timeout=10)


def run(Client, expect, sql, env, browser, process, await_health, web_port, root):
    ops = Client().login("ops")
    sh = Client().login("warehouse-sh")
    gz = Client().login("warehouse-gz")
    support = Client().login("support")

    def po(warehouse, sku, qty, key):
        return expect(ops.post("/purchase-orders", {
            "warehouseCode": warehouse, "supplierName": "隔离验收供应商",
            "lines": [{"skuCode": sku, "quantity": qty}],
        }, key), 201)["poNo"]

    def stock(worker, number, qty):
        receipt = expect(worker.post(f"/purchase-orders/{number}/receipts",
            {"lineNo":1,"binCode":"RECV-01","quantity":qty},f"{number}-rc"),201)
        expect(worker.post(f'/receipts/{receipt["receiptNo"]}/put-aways',
            {"destinationBinCode":"A-01-01","quantity":qty},f"{number}-pa"),201)

    def payload(channel, external, *lines):
        return {"channel":channel,"channelOrderNo":external,"paidAt":PAID,
                "lines":[{"skuCode":sku,"quantity":qty} for sku,qty in lines]}

    def route(number, key, client=ops):
        return client.post(f"/orders/{number}/route", key=key)

    def assert_ledger():
        assert sql("""
            SELECT COUNT(*) FROM stock_balance b
            LEFT JOIN (SELECT balance_id,SUM(delta_on_hand) h,SUM(delta_reserved) r FROM stock_movement GROUP BY balance_id) m ON m.balance_id=b.id
            LEFT JOIN (SELECT balance_id,SUM(qty) q FROM stock_reservation WHERE status='ACTIVE' GROUP BY balance_id) r ON r.balance_id=b.id
            WHERE b.on_hand<>COALESCE(m.h,0) OR b.reserved<>COALESCE(m.r,0) OR b.reserved<>COALESCE(r.q,0)
            """) == "0"

    request = payload("DIRECT","SAMPLE-001",(SKU,10))
    if browser:
        no = a05_browser(browser,process,await_health,web_port,root,env["HF_DEMO_PASSWORD"])
    else:
        stock(gz, po("GZ", SKU, 4, "m2-gz-po"), 4)
        created = expect(ops.post("/orders",request,"sample-create"),201)
        no = created["orderNo"]
        assert created["routeStatus"] == "RECEIVED" and created["fulfillments"] == []
    replay = expect(ops.post("/orders",{**request,"paidAt":"2026-01-01T08:00:00+08:00"},"sample-replay"),200)
    assert replay["orderNo"] == no
    expect(ops.post("/orders",{**request,"paidAt":123456},"sample-invalid-time"),400,"INVALID_INPUT")
    expect(ops.post("/orders",{**request,"lines":request["lines"]*2},"sample-duplicate-sku"),400,"INVALID_INPUT")
    expect(ops.post("/orders",payload("DIRECT","SAMPLE-001",(SKU,9)),"sample-conflict"),409,"CHANNEL_ORDER_CONFLICT")
    other = expect(ops.post("/orders",payload("DOUYIN","SAMPLE-001",(SKU,1)),"sample-other"),201)
    assert other["orderNo"] != no
    expect(sh.call(f"/orders/{no}"),403,"FORBIDDEN")
    assert expect(support.call(f"/orders/{no}"),200)["allowedActions"] == []
    expect(support.post(f"/orders/{no}/route",key="support-route"),403,"FORBIDDEN")
    allocated = expect(route(no, "sample-route"),200)
    assert allocated["routeStatus"] == "ALLOCATED"
    assert {f["warehouseCode"]: f["lines"][0]["quantity"] for f in allocated["fulfillments"]} == {"SH":8,"GZ":2}
    assert allocated["lines"][0]["reservedQty"] == 10
    assert allocated["fulfillmentStatus"] == "READY_TO_PICK" and allocated["deliveryStatus"] == "NOT_STARTED"
    assert expect(route(no,"sample-route-again"),200) == allocated
    assert sql(f"""
        SELECT w.code,b.on_hand,b.reserved,ol.line_no,fl.qty
        FROM channel_order o JOIN fulfillment_order f ON f.order_id=o.id
        JOIN warehouse w ON w.id=f.warehouse_id JOIN fulfillment_line fl ON fl.fulfillment_id=f.id
        JOIN channel_order_line ol ON ol.id=fl.order_line_id AND ol.order_id=o.id
        JOIN stock_reservation r ON r.fulfillment_line_id=fl.id JOIN stock_balance b ON b.id=r.balance_id
        WHERE o.order_no='{no}' ORDER BY w.route_priority
        """) == "SH\t8\t8\t1\t8\nGZ\t4\t2\t1\t2"
    assert_ledger()
    assert expect(ops.call("/orders?channel=DIRECT&channelOrderNo=SAMPLE-001"),200)["items"][0]["orderNo"] == no
    inspection = sql((root / "infra/mysql/verify-m2.sql").read_text(), app=True)
    assert no in inspection and "\tSH\t" in inspection and "\tGZ\t" in inspection
    print(f"A05 {'browser + ' if browser else ''}real MySQL: DIRECT/SAMPLE-001 / {no}, SH=8 and GZ=2 reserved, on-hand=12")

    sql("INSERT INTO sku(code,name) VALUES('M2-LACK','第二行缺货品'),('M2-LAST','最后一件并发品'),('M2-FAIL','技术故障测试品')")
    multi = payload("DIRECT","M2-MULTI",(SKU,1),("M2-LACK",1))
    multi_no = expect(ops.post("/orders",multi,"m2-multi"),201)["orderNo"]
    shortage = expect(route(multi_no,"m2-short"),200)
    assert shortage["routeStatus"] == "SHORTAGE" and not shortage["fulfillments"]
    assert shortage["shortages"] == [{"skuCode":"M2-LACK","requestedQty":1,"availableQty":0,"missingQty":1}]
    shortage_cancel = expect(ops.post("/orders",payload("DIRECT","M2-CANCEL-SHORT",("M2-LACK",2)),"m2-c-short"),201)["orderNo"]
    assert expect(route(shortage_cancel,"m2-c-short-route"),200)["routeStatus"] == "SHORTAGE"
    counts = sql("SELECT COUNT(*) FROM stock_movement")
    assert expect(ops.post(f"/orders/{shortage_cancel}/cancel",{"reason":"缺货取消"},"m2-c-short-cancel"),200)["shortages"] == []
    assert sql("SELECT COUNT(*) FROM stock_movement") == counts
    assert_ledger()
    stock(sh, po("SH","M2-LACK",1,"lack-po"),1)
    assert expect(route(multi_no,"m2-short"),200) == shortage
    retried = expect(route(multi_no,"m2-retry"),200)
    assert retried["routeStatus"] == "ALLOCATED" and not retried["shortages"]
    assert len(retried["fulfillments"]) == 2
    cancelled = expect(ops.post(f"/orders/{multi_no}/cancel",{"reason":"隔离验证"},"m2-cancel"),200)
    assert cancelled["routeStatus"] == "CANCELLED" and all(l["reservedQty"] == 0 for l in cancelled["lines"])
    assert all(f["status"] == "CANCELLED" for f in cancelled["fulfillments"])
    expect(ops.post(f"/orders/{multi_no}/cancel",{"reason":"隔离验证"},"m2-cancel-again"),200)
    expect(ops.post(f"/orders/{multi_no}/cancel",{"reason":"不同原因"},"m2-cancel-conflict"),409,"INVALID_STATE")
    assert_ledger()

    stock(sh,po("SH","M2-FAIL",1,"fail-sh"),1)
    stock(gz,po("GZ","M2-FAIL",1,"fail-gz"),1)
    fail_no = expect(ops.post("/orders",payload("DIRECT","M2-FAIL",("M2-FAIL",2)),"m2-fail"),201)["orderNo"]
    fail_balance = int(sql("""
        SELECT sb.id FROM stock_balance sb JOIN sku s ON s.id=sb.sku_id JOIN bin b ON b.id=sb.bin_id
        JOIN warehouse w ON w.id=b.warehouse_id WHERE s.code='M2-FAIL' AND w.code='GZ' AND b.type='PICKABLE'
        """))
    sql(f"""
        DELIMITER //
        CREATE TRIGGER m2_second_warehouse_failure BEFORE INSERT ON stock_movement FOR EACH ROW
        BEGIN
          IF NEW.movement_type='RESERVE' AND NEW.balance_id={fail_balance} THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='m2 isolated second warehouse rollback';
          END IF;
        END//
        DELIMITER ;
        """)
    try:
        expect(route(fail_no,"m2-failure-route"),500,"INTERNAL_ERROR")
        after = expect(ops.call(f"/orders/{fail_no}"),200)
        assert after["routeStatus"] == "RECEIVED" and after["fulfillments"] == [] and after["shortages"] == []
        assert sql("SELECT COUNT(*) FROM command_receipt WHERE idempotency_key='m2-failure-route'") == "0"
        assert_ledger()
    finally:
        sql("DROP TRIGGER m2_second_warehouse_failure")
    assert expect(route(fail_no,"m2-failure-route"),200)["routeStatus"] == "ALLOCATED"
    assert_ledger()
    print("A06 shortage/no partial commitment, new-key retry, cancel+release, technical rollback and same-key retry passed")

    stock(sh, po("SH","M2-LAST",1,"race-po"),1)
    clients = [Client().login("ops") for _ in range(2)]
    numbers = [expect(c.post("/orders",payload(channel,"M2-RACE",("M2-LAST",1)),f"m2-race-post-{i}"),201)["orderNo"]
               for i,(c,channel) in enumerate(zip(clients,("DIRECT","DOUYIN")))]
    barrier = Barrier(2)
    def compete(i):
        barrier.wait(timeout=10)
        return expect(route(numbers[i],f"m2-race-route-{i}",clients[i]),200)["routeStatus"]
    with ThreadPoolExecutor(2) as pool:
        assert sorted(pool.map(compete,range(2))) == ["ALLOCATED","SHORTAGE"]
    assert_ledger()
    print("A07 two channels compete: one allocated, one shortage; real ledger reconciles")
