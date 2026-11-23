"""M4 outage, recovery and restore on the test project's own Compose volumes."""
from contextlib import nullcontext
import json
import subprocess
import tempfile
import time

from m3 import Web


def pick_order(Client,expect,external,sku,actor):
    ops=Client().login("ops")
    worker=Client().login(actor)
    number=expect(ops.post("/orders",{"channel":"DOUYIN","channelOrderNo":external,
        "paidAt":"2026-01-01T00:00:00Z","lines":[{"skuCode":sku,"quantity":1}]},external+"-accept"),201)["orderNo"]
    fulfillment=expect(ops.post(f"/orders/{number}/route",key=external+"-route"),200)["fulfillments"][0]["fulfillmentNo"]
    target="/fulfillments/"+fulfillment
    task=expect(worker.call(target),200)["tasks"][0]["taskNo"]
    expect(worker.post(f"{target}/tasks/{task}/pick",key=external+"-pick"),200)
    return number,target,worker


def before_upgrade(Client,expect,sql,legacy):
    if not legacy:
        return None
    number,target,worker=pick_order(Client,expect,"V4-UNSHIPPED","M1-RACE","warehouse-sh")
    old_dispatch=expect(worker.post(target+"/dispatch",key="v4-alone"),201)["dispatchNo"]
    shipments=sql("SELECT id,shipment_no,dispatch_id,status,delivered_at FROM shipment ORDER BY id")
    return number,target,old_dispatch,shipments


def eventually(check,timeout=60):
    stop=time.monotonic()+timeout
    while time.monotonic()<stop:
        value=check()
        if value:
            return value
        time.sleep(.8)
    raise AssertionError("M4 state did not become ready before timeout")


def run(Client,expect,sql,env,browser,process,await_health,web_port,root,jar,compose,project,
        restore_port,restore_api,historical,native_redis,log_path):
    ops=Client().login("ops")
    support=Client().login("support")
    expect(support.call("/operations/summary"),403,"FORBIDDEN")
    expect(support.call("/operations/outbox"),403,"FORBIDDEN")

    def docker(*args,capture=False):
        return subprocess.run(compose+list(args),cwd=root,env=env,check=True,text=True,timeout=95,capture_output=capture).stdout

    def redis(*args):
        if native_redis:
            cli_env={k:v for k,v in env.items() if k!="REDISCLI_AUTH"}
            return subprocess.check_output(["redis-cli","-p",env["HF_REDIS_PORT"],*args],env=cli_env,text=True,timeout=5)
        return docker("exec","-T","redis","redis-cli",*args,capture=True)

    def events(number):
        return expect(ops.call(f"/operations/outbox?dispatchNo={number}"),200)["items"]

    def state(number,wanted):
        rows=events(number)
        return next((row for row in rows if row["status"]==wanted),None)

    def published_and_built(event_id,dispatch_no):
        return state(dispatch_no,"PUBLISHED") and sql(f"SELECT COUNT(*) FROM consumer_inbox WHERE event_id='{event_id}'")=="1"

    if historical:
        _,old_target,old_dispatch,old_shipments=historical
        assert sql("SELECT COUNT(*) FROM outbox_event")=="0"
        assert old_shipments==sql("SELECT id,shipment_no,dispatch_id,status,delivered_at FROM shipment ORDER BY id")
        assert events(old_dispatch)==[] and expect(ops.call(old_target),200)["shipment"] is None
        assert expect(ops.post(old_target+"/shipment",key="v4-manual-after-v5"),201)["dispatchNo"]==old_dispatch
        print("V4→V5: old dispatches did not enqueue; old signed shipments retained; legacy unshipped order can be built manually")

    cached=expect(ops.call("/operations/summary"),200)
    assert "PONG" in redis("ping")
    ttl=int(redis("TTL","hf:operations:summary:v1"))
    assert 0<ttl<=30, ttl
    assert expect(ops.call("/operations/summary"),200)==cached

    number,target,worker=pick_order(Client,expect,"SAMPLE-002","HOME-BOX-01","warehouse-gz")
    first=expect(worker.post(target+"/dispatch",key="m4-broker-off"),201)
    assert expect(ops.call(target),200)["shipment"] is None
    dispatch=first["dispatchNo"]
    rows=events(dispatch)
    assert len(rows)==1 and rows[0]["dispatchNo"]==dispatch and rows[0]["eventType"]=="FulfillmentDispatched.v1"
    eid=rows[0]["eventId"]
    def await_exhaustion():
        if failed:=state(dispatch,"FAILED"):
            return failed
        # Move only the clock deadline in this isolated DB; the worker still makes and records every real failed send.
        sql(f"""UPDATE outbox_event SET next_attempt_at=CURRENT_TIMESTAMP(3)
                WHERE event_id='{eid}' AND status='PENDING' AND attempt>0 AND last_error IS NOT NULL""")
        return None
    failed=eventually(await_exhaustion,timeout=28)
    assert failed["attempt"]==5 and failed["eventId"]==eid and failed["lastError"]
    assert sql(f"SELECT COUNT(*) FROM consumer_inbox WHERE event_id='{eid}'")=="0"
    assert expect(ops.call(target),200)["shipment"] is None
    print(f"A11 outage: {number} / {dispatch} committed while Broker was down; {eid} reached FAILED after 5 real attempts (test-only expedited deadlines)")

    with process("vite M4",["node","node_modules/vite/bin/vite.js"],root/"frontend") if browser else nullcontext():
        web=None
        if browser:
            web=Web(browser,f"http://127.0.0.1:{web_port}",env["HF_DEMO_PASSWORD"],root)
            await_health(web.url+"/api/health")
            tab=json.loads(web("newtab",web.url,"--json"))["tabId"]
        try:
            if web:
                web.login("ops","/operations")
                web("wait",f'.mono:text-is("{eid}")')
                web("fill",'input[placeholder="DS-…"]',dispatch)
                web("click",'button:has-text("查询")')
                web("wait",'button:has-text("重新投递")')
            docker("up","-d","--wait","--wait-timeout","65","mq-broker")
            if web:
                web("click",'button:has-text("重新投递")')
            else:
                retry=f"/operations/outbox/{eid}/retry"
                after_retry=expect(ops.post(retry,key="manual-outbox-retry"),200)
                assert after_retry["eventId"]==eid and after_retry["attempt"]==0
                assert expect(ops.post(retry,key="manual-outbox-retry"),200)==after_retry
            eventually(lambda:published_and_built(eid,dispatch),timeout=90)
            if web:
                web("click",'button:has-text("刷新")')
                web("wait",'.status-chip:text-is("Broker 已确认")')
        finally:
            if web:
                web("closetab",str(tab))
    first_shipment=expect(ops.call(target),200)["shipment"]["shipmentNo"]
    traced=sql((root/"infra/mysql/verify-m4.sql").read_text(),app=True)
    assert eid in traced and first_shipment in traced and "\tRETRY_OUTBOX\t" in traced
    assert sql(f"SELECT actor_key FROM operation_log WHERE action='CREATE_SHIPMENT' AND entity_no='{first_shipment}'")=="system:shipment-consumer"

    # Isolated fault fixture: replay as if the publisher had crashed after broker ACK.
    sql(f"UPDATE outbox_event SET status='PENDING',next_attempt_at=CURRENT_TIMESTAMP(3),published_at=NULL WHERE event_id='{eid}'")
    eventually(lambda:published_and_built(eid,dispatch),timeout=35)
    eventually(lambda:f"ignored replay eventKey={eid}" in log_path.read_text(),timeout=55)
    assert expect(ops.call(target),200)["shipment"]["shipmentNo"]==first_shipment
    assert sql(f"SELECT COUNT(*) FROM dispatch d JOIN shipment s ON s.dispatch_id=d.id WHERE d.dispatch_no='{dispatch}'")=="1"

    second_no,second_target,second_worker=pick_order(Client,expect,"M4-CONSUMER-FAIL","HOME-BOX-01","warehouse-gz")
    sql("""CREATE TRIGGER m4_consumer_block BEFORE INSERT ON shipment
           FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='m4 isolated consumer failure';""")
    try:
        second=expect(second_worker.post(second_target+"/dispatch",key="m4-consumer-fail"),201)
        second_dispatch=second["dispatchNo"]
        second_event=eventually(lambda:state(second_dispatch,"PUBLISHED"))
        second_id=second_event["eventId"]
        eventually(lambda:f"consumer failed eventKey={second_id}" in log_path.read_text(),timeout=60)
        assert expect(ops.call(second_target),200)["shipment"] is None
        assert sql(f"SELECT COUNT(*) FROM consumer_inbox WHERE event_id='{second_id}'")=="0"
    finally:
        sql("DROP TRIGGER m4_consumer_block")
    result=ops.post(second_target+"/shipment",key="m4-manual-consumer-race")
    assert result[0] in (200,201),result
    eventually(lambda:published_and_built(second_id,second_dispatch),timeout=110)
    assert sql(f"SELECT COUNT(*) FROM dispatch d JOIN shipment s ON s.dispatch_id=d.id WHERE d.dispatch_no='{second_dispatch}'")=="1"
    print(f"A11 {'browser + ' if browser else ''}RocketMQ recovery: {eid} → {first_shipment}; real replay deduped; published/consume-failed separate for {second_no}")

    if native_redis:
        redis("shutdown","nosave")
    else:
        docker("stop","redis")
    # Change MySQL through the business API while Redis is stopped, then compare all three counters.
    new_order=expect(ops.post("/orders",{"channel":"DOUYIN","channelOrderNo":"M4-NO-CACHE","paidAt":"2026-01-01T00:00:00Z",
        "lines":[{"skuCode":"HOME-BOX-01","quantity":1}]},"m4-no-cache-order"),201)["orderNo"]
    assert expect(ops.post(f"/orders/{new_order}/route",key="m4-no-cache-route"),200)["routeStatus"]=="SHORTAGE"
    start=time.monotonic()
    summary=expect(ops.call("/operations/summary"),200)
    duration=time.monotonic()-start
    expected=sql("""
         SELECT (SELECT COUNT(*) FROM channel_order WHERE route_status='SHORTAGE'),
                (SELECT COUNT(*) FROM fulfillment_order WHERE status NOT IN ('DISPATCHED','CANCELLED')),
                (SELECT COUNT(*) FROM outbox_event WHERE status='FAILED');
        """)
    assert [summary[k] for k in ("shortageCount","unshippedFulfillmentCount","failedEventCount")]==list(map(int,expected.split("\t")))
    assert duration<3, duration
    assert expect(support.call(f"/orders/{number}"),200)["fulfillments"][0]["shipment"]["shipmentNo"]==first_shipment
    restore(Client,expect,sql,env,process,await_health,root,jar,project,restore_port,restore_api,number,first_shipment)
    print(f"A12 Redis off: matching MySQL summary in {duration:.2f}s, transactions and detail still available; isolated backup restored {number} / {first_shipment}")


def restore(Client,expect,sql,env,process,await_health,root,jar,project,mysql_port,api_port,number,shipment):
    restored=dict(env,HF_MYSQL_PORT=str(mysql_port),HF_BACKEND_PORT=str(api_port),HF_SEED="false",
                  HF_EVENTS_ENABLED="false",HF_OPERATIONS_CACHE="false")
    compose=["docker","compose","-p",project+"-restore"]
    def exec_mysql(text,app):
        credentials='MYSQL_PWD="$HF_DB_APP_PASSWORD" mysql -h127.0.0.1 -uhf_app' if app else 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot'
        return subprocess.run(compose+["exec","-T","mysql","sh","-ec",credentials+" -N --batch home_fulfillment"],
                              cwd=root,env=restored,input=text,text=True,check=True,capture_output=True,timeout=40).stdout.strip()
    query="""
        SELECT id,on_hand,reserved FROM stock_balance ORDER BY id;
        SELECT event_id,status FROM outbox_event ORDER BY event_id;
        SELECT consumer_name,event_id FROM consumer_inbox ORDER BY event_id;
        """
    source=sql(query,app=True)
    try:
        subprocess.run(compose+["up","-d","--wait","--wait-timeout","65","mysql"],cwd=root,env=restored,check=True)
        with tempfile.TemporaryDirectory() as temp:
            from pathlib import Path
            backup=Path(temp)/"snapshot.sql"
            subprocess.run(["bash","backup.sh",str(backup)],cwd=root,env=dict(env,COMPOSE_PROJECT_NAME=project),check=True,timeout=50)
            with backup.open("rb") as file:
                subprocess.run(compose+["exec","-T","mysql","sh","-ec",'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot'],
                               cwd=root,env=restored,stdin=file,check=True,timeout=50)
        with (root/"infra/mysql/ensure-app-user.sh").open() as script:
            subprocess.run(compose+["exec","-T","mysql","bash","-s"],cwd=root,env=restored,stdin=script,check=True)
        assert source==exec_mysql(query,app=True)
        assert "DELIVERED" in exec_mysql((root/"infra/mysql/verify-m3.sql").read_text(),app=True)
        with process("restore backend",["java","-jar",str(jar)],root,restored):
            host=f"http://127.0.0.1:{api_port}"
            await_health(host+"/actuator/health/readiness")
            order=expect(Client(host).login("support").call(f"/orders/{number}"),200)
            assert order["fulfillments"][0]["shipment"]["shipmentNo"]==shipment
    finally:
        subprocess.run(compose+["down","--volumes"],cwd=root,env=restored,check=True)
