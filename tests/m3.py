"""M3 acceptance; M2 creates the standard allocated order first."""

from concurrent.futures import ThreadPoolExecutor
from contextlib import nullcontext
from datetime import datetime, timedelta
import json
import subprocess
from threading import Barrier


def snapshot(sql):
    return sql("""
        SELECT id,on_hand,reserved FROM stock_balance ORDER BY id;
        SELECT id,reservation_no,fulfillment_line_id,balance_id,qty,status FROM stock_reservation ORDER BY id;
        """)


class Web:
    def __init__(self, binary, url, password, root):
        self.binary, self.url, self.password, self.root = binary, url, password, root

    def __call__(self, *args):
        result = subprocess.run([self.binary,*args],cwd=self.root,text=True,capture_output=True,timeout=25)
        if result.returncode:
            raise AssertionError(f"M3 browser {args[0]}: {result.stderr if args[0]!='fill' else '(redacted)'}")
        return result.stdout.strip()

    def login(self, actor, path):
        self("goto",f"{self.url}/login?next={path}")
        self("wait",'input[autocomplete="username"]')
        self("fill",'input[autocomplete="username"]',actor)
        self("fill",'input[autocomplete="current-password"]',self.password)
        self("click",'button[type="submit"]')
        self("wait",'.topbar')


def run(Client, expect, sql, env, browser, process, await_health, web_port, root):
    web = Web(browser,f"http://127.0.0.1:{web_port}",env["HF_DEMO_PASSWORD"],root) if browser else None
    with process("vite M3",["node","node_modules/vite/bin/vite.js"],root/"frontend") if web else nullcontext():
        if web:
            await_health(web.url+"/api/health")
            tab=json.loads(web("newtab",web.url,"--json"))["tabId"]
        try:
            exercise(Client,expect,sql,web,root)
        finally:
            if web:
                web("closetab",str(tab))


def exercise(Client, expect, sql, web, root):
    ops = Client().login("ops")
    sh = Client().login("warehouse-sh")
    gz = Client().login("warehouse-gz")
    support = Client().login("support")
    order = expect(ops.call("/orders?channel=DIRECT&channelOrderNo=SAMPLE-001"),200)["items"][0]["orderNo"]
    order_path=f"/orders/{order}"
    sh_no, gz_no = [f["fulfillmentNo"] for f in expect(ops.call(order_path),200)["fulfillments"]]
    sh_path, gz_path = f"/fulfillments/{sh_no}", f"/fulfillments/{gz_no}"
    tasks = [expect(worker.call(path),200)["tasks"] for worker,path in ((sh,sh_path),(gz,gz_path))]
    assert all(len(task)==1 and task[0]["status"]=="PENDING" for task in tasks)
    assert sql("""
        SELECT COUNT(*) FROM stock_reservation r LEFT JOIN pick_task p ON p.reservation_id=r.id
        WHERE p.id IS NULL OR (r.status='RELEASED' AND p.status<>'CANCELLED') OR (r.status='ACTIVE' AND p.status<>'PENDING')
        """) == "0"
    assert expect(sh.call("/fulfillments?warehouseCode=GZ"),200)["total"]==0
    expect(sh.call(gz_path),403,"FORBIDDEN")
    expect(sh.post(gz_path+"/dispatch",key="wrong-warehouse"),403,"FORBIDDEN")
    expect(ops.post(sh_path+"/dispatch",key="wrong-actor"),403,"FORBIDDEN")
    expect(sh.post(sh_path+"/dispatch",key="not-picked"),409,"INVALID_STATE")
    expect(ops.post(sh_path+"/shipment",key="not-dispatched"),409,"INVALID_STATE")
    expect(sh.call("/shipments"),403,"FORBIDDEN")

    def home_balances():
        return sql("""
            SELECT w.code,sb.on_hand,sb.reserved FROM stock_balance sb JOIN sku s ON s.id=sb.sku_id
            JOIN bin b ON b.id=sb.bin_id JOIN warehouse w ON w.id=b.warehouse_id
            WHERE s.code='HOME-BOX-01' AND b.type='PICKABLE' ORDER BY w.route_priority
            """)

    before_pick=snapshot(sql)
    movement_count=sql("SELECT COUNT(*) FROM stock_movement")
    task_path=sh_path+f'/tasks/{tasks[0][0]["taskNo"]}/pick'
    if web:
        web.login("warehouse-sh",f"/warehouse/outbound/{sh_no}")
        web("wait",'button:has-text("确认拣货")')
        web("click",'button:has-text("确认拣货")')
        web("wait",'button:has-text("确认出库")')
    else:
        expect(sh.post(task_path,key="sh-pick"),200)
    assert before_pick==snapshot(sql) and movement_count==sql("SELECT COUNT(*) FROM stock_movement")
    assert expect(sh.post(task_path,key="sh-pick-again"),200)["status"]=="PICKED"
    assert expect(ops.call(order_path),200)["fulfillmentStatus"]=="PICKING"
    expect(ops.post(order_path+"/cancel",{"reason":"一仓已拣"},"after-pick"),409,"INVALID_STATE")
    assert before_pick==snapshot(sql)

    if web:
        web("click",'button:has-text("确认出库")')
        web("wait",'.status-chip:text-is("已出库")')
        first=expect(sh.post(sh_path+"/dispatch",key="sh-repeat-dispatch"),200)
    else:
        first=expect(sh.post(sh_path+"/dispatch",key="sh-dispatch"),201)
        assert expect(sh.post(sh_path+"/dispatch",key="sh-dispatch"),201)==first
        assert expect(sh.post(sh_path+"/dispatch",key="sh-repeat-dispatch"),200)==first
    assert home_balances()=="SH\t0\t0\nGZ\t4\t2"
    partial=expect(support.call(order_path),200)
    assert partial["fulfillmentStatus"]=="PARTIALLY_DISPATCHED" and partial["deliveryStatus"]=="AWAITING_SHIPMENT"
    assert partial["lines"][0]["dispatchedQty"]==8 and partial["lines"][0]["reservedQty"]==2
    assert partial["fulfillments"][0]["dispatch"]["dispatchNo"]==first["dispatchNo"]

    if web:
        web.login("warehouse-gz",f"/warehouse/outbound/{gz_no}")
        web("wait",'button:has-text("确认拣货")')
        web("click",'button:has-text("确认拣货")')
        web("wait",'button:has-text("确认出库")')
        web("click",'button:has-text("确认出库")')
        web("wait",'.status-chip:text-is("已出库")')
        second=expect(gz.post(gz_path+"/dispatch",key="gz-repeat"),200)
    else:
        expect(gz.post(gz_path+f'/tasks/{tasks[1][0]["taskNo"]}/pick',key="gz-pick"),200)
        second=expect(gz.post(gz_path+"/dispatch",key="gz-dispatch"),201)
    assert home_balances()=="SH\t0\t0\nGZ\t2\t0"
    assert expect(ops.call(order_path),200)["fulfillmentStatus"]=="DISPATCHED"
    assert sql(f"""
        SELECT delta_on_hand,delta_reserved FROM stock_movement WHERE movement_type='DISPATCH'
        AND biz_no IN ('{first["dispatchNo"]}','{second["dispatchNo"]}') ORDER BY id
        """)=="-8\t-8\n-2\t-2"
    multi=expect(ops.post("/orders",{"channel":"DIRECT","channelOrderNo":"M3-TWO-TASKS",
        "paidAt":"2026-01-01T00:00:00Z","lines":[{"skuCode":"M2-LACK","quantity":1},{"skuCode":"M1-RACE","quantity":1}]},
        "m3-two-tasks"),201)["orderNo"]
    multi_fo=expect(ops.post(f"/orders/{multi}/route",key="m3-two-tasks-route"),200)["fulfillments"][0]["fulfillmentNo"]
    multi_path=f"/fulfillments/{multi_fo}"
    multi_tasks=expect(sh.call(multi_path),200)["tasks"]
    assert len(multi_tasks)==2
    multi_stock=snapshot(sql)
    for index,task_row in enumerate(multi_tasks):
        progress=expect(sh.post(f'{multi_path}/tasks/{task_row["taskNo"]}/pick',key=f"m3-two-pick-{index}"),200)
        assert progress["status"]==("PICKING" if index==0 else "PICKED")
        if index==0:
            expect(sh.post(multi_path+"/dispatch",key="m3-partial-pick"),409,"INVALID_STATE")
    assert snapshot(sql)==multi_stock
    print(f"A08 {'browser + ' if web else ''}real MySQL: {order} / {first['dispatchNo']} / {second['dispatchNo']}, picked stock unchanged; SH 0/0 GZ 2/0")

    # The first order has two allocated warehouses before the race. Both contenders share the order lock.
    race=expect(ops.call("/orders?channelOrderNo=M2-FAIL"),200)["items"][0]["orderNo"]
    race_path=f"/orders/{race}"
    race_detail=expect(ops.call(race_path),200)
    race_fo=race_detail["fulfillments"][0]["fulfillmentNo"]
    race_fpath=f"/fulfillments/{race_fo}"
    task=expect(sh.call(race_fpath),200)["tasks"][0]["taskNo"]
    race_before=snapshot(sql)
    barrier=Barrier(2)
    def contend(kind):
        barrier.wait(timeout=10)
        if kind=="pick":
            return sh.post(f"{race_fpath}/tasks/{task}/pick",key="m3-race-pick")
        return ops.post(race_path+"/cancel",{"reason":"竞态验收"},"m3-race-cancel")
    with ThreadPoolExecutor(2) as pool:
        pick_result,cancel_result=pool.map(contend,["pick","cancel"])
    assert {pick_result[0],cancel_result[0]}=={200,409}
    if pick_result[0]==200:
        assert race_before==snapshot(sql)
        assert expect(ops.call(race_path),200)["routeStatus"]=="ALLOCATED"
        expect(cancel_result,409,"INVALID_STATE")
    else:
        expect(pick_result,409,"INVALID_STATE")
        assert all(f["status"]=="CANCELLED" for f in cancel_result[1]["fulfillments"])
        assert cancel_result[1]["lines"][0]["reservedQty"]==0

    # M2's old cancelled reservations are mapped to tasks during migration; V4's own cancel path must too.
    new=expect(ops.post("/orders",{"channel":"DIRECT","channelOrderNo":"M3-UNPICKED",
        "paidAt":"2026-01-01T00:00:00Z","lines":[{"skuCode":"HOME-BOX-01","quantity":1}]},"m3-unpicked"),201)["orderNo"]
    new_path=f"/orders/{new}"
    new_fo=expect(ops.post(new_path+"/route",key="m3-unpicked-route"),200)["fulfillments"][0]["fulfillmentNo"]
    cancelled=expect(ops.post(new_path+"/cancel",{"reason":"未拣验收"},"m3-unpicked-cancel"),200)
    assert cancelled["lines"][0]["reservedQty"]==0 and home_balances()=="SH\t0\t0\nGZ\t2\t0"
    assert expect(gz.call(f"/fulfillments/{new_fo}"),200)["tasks"][0]["status"]=="CANCELLED"
    print(f"A09 cancel vs pick exclusive ({pick_result[0]}/{cancel_result[0]}); unpicked cancel released without debiting on-hand")

    if web:
        web.login("ops",f"/orders/{order}")
        web("wait",f'a[href="/warehouse/outbound/{sh_no}"]')
        web("click",f'a[href="/warehouse/outbound/{sh_no}"]')
        web("wait",'button:has-text("建模拟运单")')
        web("click",'button:has-text("建模拟运单")')
        web("wait",'button:has-text("提交轨迹")')
        created=expect(ops.post(sh_path+"/shipment",key="ship-sh-replay"),200)
    else:
        created=expect(ops.post(sh_path+"/shipment",key="ship-sh"),201)
        assert expect(ops.post(sh_path+"/shipment",key="ship-sh"),201)==created
        assert expect(ops.post(sh_path+"/shipment",key="ship-sh-replay"),200)==created
    track=f'/shipments/{created["shipmentNo"]}/events'
    assert created["carrier"]=="SIM" and created["dispatchNo"]==first["dispatchNo"]
    expect(support.post(track,{"sourceEventNo":"no","type":"DELIVERED","occurredAt":first["dispatchedAt"]},"support-track"),403,"FORBIDDEN")
    expect(ops.post(track,{"sourceEventNo":"old","type":"IN_TRANSIT","occurredAt":"2000-01-01T00:00:00Z"},"old-track"),409,"TRACKING_EVENT_OUT_OF_ORDER")
    before_ship=snapshot(sql)
    if web:
        web("click",'button:has-text("提交轨迹")')
        web("wait",'.status-chip:text-is("在途")')
        web("select",'select:has(option[value="DELIVERED"])',"DELIVERED")
        web("click",'button:has-text("提交轨迹")')
        web("wait",'.status-chip:text-is("已签收")')
        delivered=expect(support.call(f'/shipments/{created["shipmentNo"]}'),200)
        transit=delivered["events"][0]
    else:
        transit={"sourceEventNo":"SH-1","type":"IN_TRANSIT","occurredAt":first["dispatchedAt"]}
        expect(ops.post(track,transit,"sh-transit"),200)
        delivered=expect(ops.post(track,{**transit,"sourceEventNo":"SH-2","type":"DELIVERED"},"sh-sign"),200)
    assert delivered["status"]=="DELIVERED" and len(delivered["events"])==2
    assert expect(ops.post(track,transit,"track-replay"),200)["status"]=="DELIVERED"
    expect(ops.post(track,{**transit,"type":"DELIVERED"},"track-conflict"),409,"TRACKING_EVENT_CONFLICT")
    newest=(datetime.fromisoformat(delivered["deliveredAt"].replace("Z","+00:00"))+timedelta(seconds=1)).isoformat()
    expect(ops.post(track,{**transit,"sourceEventNo":"new-after-signed","occurredAt":newest},"terminal-guard"),409,"INVALID_STATE")
    assert expect(support.call(order_path),200)["deliveryStatus"]=="IN_PROGRESS"
    ops_clients=[ops,Client().login("ops")]
    start_build=Barrier(2)
    def build_same_shipment(index):
        start_build.wait(timeout=10)
        return ops_clients[index].post(gz_path+"/shipment",key=f"ship-gz-{index}")
    with ThreadPoolExecutor(2) as pool:
        builds=list(pool.map(build_same_shipment,range(2)))
    assert sorted(status for status,_ in builds)==[200,201] and builds[0][1]==builds[1][1]
    second_ship=builds[0][1]
    gz_track=f'/shipments/{second_ship["shipmentNo"]}/events'
    gz_base=datetime.fromisoformat(second["dispatchedAt"].replace("Z","+00:00"))
    gz_event=(gz_base+timedelta(seconds=2)).isoformat()
    expect(ops.post(gz_track,{"sourceEventNo":"GZ-1","type":"IN_TRANSIT","occurredAt":gz_event},"gz-transit"),200)
    expect(ops.post(gz_track,{"sourceEventNo":"GZ-LATE","type":"IN_TRANSIT","occurredAt":gz_base.isoformat()},"gz-late"),
           409,"TRACKING_EVENT_OUT_OF_ORDER")
    expect(ops.post(gz_track,{"sourceEventNo":"GZ-2","type":"DELIVERED","occurredAt":gz_event},"gz-sign"),200)
    final=expect(support.call(order_path),200)
    assert final["deliveryStatus"]=="DELIVERED" and final["allowedActions"]==[]
    assert {"RECEIVED","ALLOCATED","PICK","DISPATCH","SHIPMENT_CREATED","IN_TRANSIT","DELIVERED"} <= {e["type"] for e in final["timeline"]}
    assert before_ship==snapshot(sql)
    assert expect(support.call("/shipments?status=DELIVERED"),200)["total"]==2
    if web:
        web.login("support",f"/orders/{order}")
        web("wait",f'a[href="/shipments/{second_ship["shipmentNo"]}"]')
        assert "运输：已签收" in web("text")
        web("click",f'a[href="/shipments/{second_ship["shipmentNo"]}"]')
        web("wait",'.status-chip:text-is("已签收")')
        assert web("js",'document.querySelector("form") === null')=="true"
    export=sql((root/"infra/mysql/verify-m3.sql").read_text(),app=True)
    assert first["dispatchNo"] in export and second_ship["shipmentNo"] in export and "\tDELIVERED\t" in export
    print(f"A10 {'browser + ' if web else ''}real MySQL: {created['shipmentNo']} / {second_ship['shipmentNo']}; terminal events/replays and support read-only passed")
