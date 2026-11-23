#!/usr/bin/env python3
"""M1 acceptance against a disposable Compose MySQL and a real backend jar."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack, contextmanager
import http.cookiejar
import hashlib
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid


ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "backend/target/home-fulfillment.jar"
parser = argparse.ArgumentParser()
parser.add_argument("--browser", metavar="BROWSE", help="optional gstack browse binary; exercises A02 through Vue")
parser.add_argument("--m2", action="store_true", help="also run M2 acceptance using the same isolated database")
parser.add_argument("--m3", action="store_true", help="also run M3 acceptance, including M2")
parser.add_argument("--upgrade-from", type=Path, metavar="M2_JAR", help="run M2 with this old jar, then restart on the same isolated DB for M3")
parser.add_argument("--m4", action="store_true", help="also run M4 using isolated Docker RocketMQ and Redis")
parser.add_argument("--upgrade-from-m3", type=Path, metavar="M3_JAR", help="use this V4 jar before starting M4")
parser.add_argument("--native-redis", action="store_true", help="M4 fallback when the Redis image cannot pull; requires redis-server and redis-cli")
options = parser.parse_args()
if options.upgrade_from and (not options.m3 or not options.upgrade_from.is_file()):
    parser.error("--upgrade-from requires --m3 and an existing M2 jar")
if options.upgrade_from_m3 and (not options.m4 or options.upgrade_from or not options.upgrade_from_m3.is_file()):
    parser.error("--upgrade-from-m3 requires --m4 and an existing M3 jar, without --upgrade-from")
if options.native_redis and not options.m4:
    parser.error("--native-redis requires --m4")


def ports(count):
    with ExitStack() as stack:
        sockets = [stack.enter_context(socket.socket()) for _ in range(count)]
        for sock in sockets:
            sock.bind(("127.0.0.1", 0))
        return [sock.getsockname()[1] for sock in sockets]


mysql_port, api_port, web_port, redis_port, nameserver_port, broker_port, restore_port, restore_api = ports(8)
project = "home-fulfillment-m1-test-" + uuid.uuid4().hex[:8]
password = secrets.token_hex(16)
env = dict(os.environ, HF_MYSQL_PORT=str(mysql_port), HF_BACKEND_PORT=str(api_port),
           HF_FRONTEND_PORT=str(web_port), HF_SEED="true", HF_DEMO_PASSWORD=password,
           MYSQL_ROOT_PASSWORD=secrets.token_hex(24), MYSQL_PASSWORD=secrets.token_hex(24),
           HF_DB_APP_PASSWORD=secrets.token_hex(24), HF_EVENTS_ENABLED="false", HF_OPERATIONS_CACHE="false",
           HF_REDIS_PORT=str(redis_port), HF_MQ_NAMESERVER_PORT=str(nameserver_port), HF_MQ_BROKER_PORT=str(broker_port))
compose = ["docker", "compose", "-p", project] + (["--profile","m4"] if options.m4 else [])
base = f"http://127.0.0.1:{api_port}"


def sql(text, app=False):
    login = ('MYSQL_PWD="$HF_DB_APP_PASSWORD" mysql -h127.0.0.1 -uhf_app' if app
             else 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot')
    return subprocess.run(compose + ["exec", "-T", "mysql", "sh", "-ec",
                          login + " -N --batch home_fulfillment"],
                          cwd=ROOT, env=env, input=text, text=True, check=True, capture_output=True).stdout.strip()


class Client:
    def __init__(self, url=base):
        self.url = url
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def call(self, path, method="GET", payload=None, headers=None):
        request = urllib.request.Request(self.url + "/api/v1" + path, method=method, headers=headers or {})
        if payload is not None:
            request.data = json.dumps(payload).encode()
            request.add_header("Content-Type", "application/json")
        try:
            reply = self.opener.open(request, timeout=10)
        except urllib.error.HTTPError as ex:
            reply = ex
        with reply:
            body = reply.read()
            return reply.code, json.loads(body) if body else None

    def post(self, path, payload=None, key=None):
        code, csrf = self.call("/auth/csrf")
        assert code == 200, csrf
        headers = {csrf["headerName"]: csrf["token"]}
        if key:
            headers["Idempotency-Key"] = key
        return self.call(path, "POST", payload, headers)

    def login(self, username):
        status, profile = self.post("/auth/login", {"username": username, "password": password})
        assert status == 200 and profile["username"] == username, (status, profile)
        return self


def expect(result, status, code=None):
    assert result[0] == status, result
    if code:
        assert result[1]["code"] == code and result[1]["requestId"], result
    return result[1]


def fixture(name):
    assert name in {"M1-RACE", "M1-ROLLBACK"}
    sql(f"""
        INSERT INTO sku(code,name) VALUES('{name}','隔离测试商品');
        INSERT INTO purchase_order(po_no,warehouse_id,supplier_name,status,created_by)
        SELECT '{name}',w.id,'隔离测试','OPEN',u.id FROM warehouse w CROSS JOIN app_user u
        WHERE w.code='SH' AND u.username='ops';
        INSERT INTO purchase_order_line(po_id,line_no,sku_id,ordered_qty)
        SELECT p.id,1,s.id,10 FROM purchase_order p CROSS JOIN sku s WHERE p.po_no='{name}' AND s.code='{name}';
        """)


def state(name):
    rows = sql(f"""
      SELECT l.received_qty,p.status,
        (SELECT COUNT(*) FROM receipt r WHERE r.po_line_id=l.id),
        (SELECT COUNT(*) FROM stock_movement m JOIN stock_balance b ON b.id=m.balance_id WHERE b.sku_id=l.sku_id),
        (SELECT COALESCE(SUM(on_hand),0) FROM stock_balance b WHERE b.sku_id=l.sku_id)
      FROM purchase_order p JOIN purchase_order_line l ON l.po_id=p.id WHERE p.po_no='{name}';
      """)
    return tuple(rows.split("\t"))


def a02(browser):
    if browser:
        with process("vite", ["node", "node_modules/vite/bin/vite.js"], ROOT / "frontend"):
            await_health(f"http://127.0.0.1:{web_port}/api/health")
            def browse(*args):
                result = subprocess.run([browser, *args], cwd=ROOT, text=True, capture_output=True, timeout=20)
                if result.returncode:
                    detail = "(redacted)" if args[0] == "fill" else result.stderr
                    raise AssertionError(f"{args[0]} failed: {detail}")
                return result.stdout
            tab = json.loads(browse("newtab", f"http://127.0.0.1:{web_port}/", "--json"))["tabId"]
            try:
                browse("wait", 'input[autocomplete="username"]')
                browse("fill", 'input[autocomplete="username"]', "warehouse-sh")
                browse("fill", 'input[autocomplete="current-password"]', password)
                browse("click", 'button[type="submit"]')
                browse("wait", 'a[href="/purchase-orders/PO-HOME-001"]')
                browse("click", 'a[href="/purchase-orders/PO-HOME-001"]')
                browse("wait", 'button:has-text("登记收货")')
                browse("click", 'button:has-text("登记收货")')
                browse("wait", 'input[type="number"]')
                browse("fill", 'input[type="number"]', "8")
                browse("js", """
                    (() => {
                        const original = window.fetch.bind(window);
                        let loseReceiptResponse = true;
                        window.fetch = async (url, init) => {
                            const response = await original(url, init);
                            if (loseReceiptResponse && init?.method === 'POST' && String(url).endsWith('/receipts')) {
                                loseReceiptResponse = false;
                                throw new TypeError('isolated test: committed response lost');
                            }
                            return response;
                        };
                        return true;
                    })()
                    """)
                browse("click", 'button[type="submit"]')
                browse("wait", 'button:has-text("重试原请求")')
                assert state("PO-HOME-001") == ("8", "PARTIAL", "1", "1", "8")
                browse("reload")
                browse("wait", 'button:has-text("重试原请求")')
                browse("click", 'button:has-text("重试原请求")')
                browse("wait", 'button:has-text("确认上架")')
                receipt_no = browse("url").strip().rsplit("/", 1)[-1]
                assert receipt_no.startswith("RC-"), receipt_no
                assert state("PO-HOME-001") == ("8", "PARTIAL", "1", "1", "8")
                browse("click", 'text=查收货流水')
                browse("wait", 'text=收货入暂存')
                assert browse("js", """
                    [...document.querySelectorAll('tbody tr')].some(row => {
                        const cells = [...row.cells].map(cell => cell.textContent.trim());
                        return cells[2]?.startsWith('RECV-01') && cells.slice(3).join(',') === '8,0,0';
                    })
                    """).strip() == "true"
                browse("back")
                browse("wait", 'button:has-text("确认上架")')
                browse("fill", 'input[type="number"]', "8")
                browse("click", 'button[type="submit"]')
                browse("wait", 'text=查双边流水')
                browse("click", 'text=查双边流水')
                browse("wait", 'text=上架 · 目标位移入')
                browse("wait", 'text=上架 · 源位移出')
                assert "+8" in browse("text") and "-8" in browse("text")
                browse("viewport", "375x812")
                widths = json.loads(browse("js", "JSON.stringify({page:document.documentElement.scrollWidth,viewport:window.innerWidth})"))
                assert widths["page"] <= widths["viewport"], widths
                browse("viewport", "1280x900")
            finally:
                subprocess.run([browser, "closetab", str(tab)], capture_output=True, timeout=10)
    else:
        client = Client().login("warehouse-sh")
        receipt = expect(client.post("/purchase-orders/PO-HOME-001/receipts",
                                     {"lineNo":1,"binCode":"RECV-01","quantity":8},"happy-receive"),201)
        receipt_no = receipt["receiptNo"]
        before = expect(client.call("/inventory/balances"),200)["items"]
        assert [(b["binCode"],b["onHand"],b["available"]) for b in before] == [("RECV-01",8,0)]
        expect(client.post(f"/receipts/{receipt_no}/put-aways",
                           {"destinationBinCode":"A-01-01","quantity":8},"happy-put"),201)
    assert state("PO-HOME-001") == ("8", "PARTIAL", "1", "3", "8")
    assert sql("""
        SELECT b.code,sb.on_hand,sb.reserved FROM stock_balance sb JOIN bin b ON b.id=sb.bin_id
        JOIN sku s ON s.id=sb.sku_id WHERE s.code='HOME-BOX-01' ORDER BY b.pick_priority;
        """)== "RECV-01\t0\t0\nA-01-01\t8\t0"
    put_away = sql("SELECT put_away_no FROM put_away")
    assert sql(f"SELECT delta_on_hand FROM stock_movement WHERE biz_no='{put_away}' ORDER BY id") == "-8\n8"
    inspection = sql((ROOT / "infra/mysql/verify-m1.sql").read_text(), app=True)
    assert f"{receipt_no}\tRECEIVE\tRECV-01\t8\t0\t8\t0\twarehouse-sh" in inspection
    assert f"{put_away}\tPUT_AWAY_OUT\tRECV-01\t-8\t0\t0\t0\twarehouse-sh" in inspection
    assert f"{put_away}\tPUT_AWAY_IN\tA-01-01\t8\t0\t8\t0\twarehouse-sh" in inspection
    print(f"A02 {'browser + lost-response/reload/retry + mobile + ' if browser else ''}real MySQL: PO-HOME-001 / {receipt_no} / {put_away}; received=8, remaining=2, movements=-8/+8")


def a03():
    fixture("M1-RACE")
    path="/purchase-orders/M1-RACE/receipts"
    payload = {"lineNo":1,"binCode":"RECV-01","quantity":8}
    expect(Client().call("/purchase-orders"),401,"UNAUTHENTICATED")
    worker=Client().login("warehouse-sh")
    expect(worker.call(path, "POST", payload, {"Idempotency-Key":"no-csrf"}),403,"FORBIDDEN")
    other_warehouse = Client().login("warehouse-gz")
    expect(other_warehouse.call("/purchase-orders/M1-RACE"),403,"FORBIDDEN")
    expect(other_warehouse.post(path,payload,"wrong-warehouse"),403,"FORBIDDEN")
    assert expect(other_warehouse.call("/purchase-orders"),200)["total"] == 0
    expect(Client().login("ops").post(path,payload,"wrong-role"),403,"FORBIDDEN")
    expect(Client().login("support").call("/inventory/balances"),403,"FORBIDDEN")
    assert state("M1-RACE")==("0","OPEN","0","0","0")
    for quantity in (0, -1, 1.5, 1000001):
        expect(worker.post(path,{**payload,"quantity":quantity},"invalid-quantity"),400,"INVALID_INPUT")
    expect(worker.post(path,payload),400,"INVALID_INPUT")
    identical_clients = [Client().login("warehouse-sh") for _ in range(2)]
    with ThreadPoolExecutor(2) as pool:
        identical = list(pool.map(lambda c: c.post(path,payload,"same-receipt"), identical_clients))
    first=expect(identical[0],201)
    assert identical[0] == identical[1]
    sorted_request = {"path":path, **payload}
    expected_hash = hashlib.sha256(json.dumps(sorted_request,sort_keys=True,separators=(",",":")).encode()).hexdigest()
    assert sql("SELECT request_hash FROM command_receipt WHERE idempotency_key='same-receipt'")==expected_hash
    replay=expect(worker.post(path,payload,"same-receipt"),201)
    assert first==replay
    expect(worker.post(path,{**payload,"quantity":7},"same-receipt"),409,"IDEMPOTENCY_CONFLICT")
    expect(worker.post(path,{**payload,"quantity":3},"too-many"),409,"OVER_RECEIPT")
    sql("DELETE uw FROM user_warehouse uw JOIN app_user u ON u.id=uw.user_id WHERE u.username='warehouse-sh'")
    try:
        expect(worker.post(path,payload,"same-receipt"),403,"FORBIDDEN")
    finally:
        sql("""INSERT INTO user_warehouse(user_id,warehouse_id)
             SELECT u.id,w.id FROM app_user u CROSS JOIN warehouse w WHERE u.username='warehouse-sh' AND w.code='SH'""")
    assert state("M1-RACE")==("8","PARTIAL","1","1","8")
    put_path=f'/receipts/{first["receiptNo"]}/put-aways'
    requests=[Client().login("warehouse-sh") for _ in range(2)]
    def submit(i):
        return requests[i].post(put_path,{"destinationBinCode":"A-01-01","quantity":5},f"race-{i}")
    with ThreadPoolExecutor(2) as pool:
        results=list(pool.map(submit,range(2)))
    assert sorted(status for status,_ in results)==[201,409],results
    expect(next(r for r in results if r[0]==409),409,"PUT_AWAY_EXCEEDS_RECEIPT")
    winner = next(i for i, result in enumerate(results) if result[0]==201)
    assert submit(winner)==results[winner]
    assert expect(worker.call(f'/receipts/{first["receiptNo"]}'),200)["putAwayQty"] == 5
    assert state("M1-RACE")==("8","PARTIAL","1","3","8")
    expect(worker.post("/auth/logout"),204)
    expect(worker.call("/auth/me"),401,"UNAUTHENTICATED")
    print("A03 concurrent same-key / stable JSON / invalid-input / conflict / over-receipt / role+warehouse / revoked-permission / CSRF / logout / concurrent put-away passed")


def a04():
    fixture("M1-ROLLBACK")
    worker=Client().login("warehouse-sh")
    path="/purchase-orders/M1-ROLLBACK/receipts"
    command={"lineNo":1,"binCode":"RECV-01","quantity":8}
    sql("""CREATE TRIGGER m1_injected_failure BEFORE INSERT ON stock_movement
          FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='m1 isolated rollback test';""")
    try:
        expect(worker.post(path,command,"retry-after-rollback"),500,"INTERNAL_ERROR")
        assert state("M1-ROLLBACK")==("0","OPEN","0","0","0")
        assert sql("SELECT COUNT(*) FROM command_receipt WHERE idempotency_key='retry-after-rollback'")=="0"
        assert sql("SELECT COUNT(*) FROM stock_balance b JOIN sku s ON s.id=b.sku_id WHERE s.code='M1-ROLLBACK'")=="0"
    finally:
        sql("DROP TRIGGER m1_injected_failure")
    expect(worker.post(path,command,"retry-after-rollback"),201)
    assert state("M1-ROLLBACK")==("8","PARTIAL","1","1","8")
    print("A04 injected movement failure rolled back PO, receipt, stock, movement and key; same key retries once")


@contextmanager
def process(name, args, cwd, child_env=None, log_path=None):
    with (open(log_path,"w+") if log_path else tempfile.TemporaryFile(mode="w+")) as log:
        p=subprocess.Popen(args,cwd=cwd,env=env if child_env is None else child_env,stdout=log,stderr=subprocess.STDOUT)
        try:
            yield
        except Exception:
            log.seek(0)
            print(f"{name} log tail:\n"+"\n".join(log.read().splitlines()[-35:]))
            raise
        finally:
            p.terminate()
            p.wait(timeout=15)


def await_health(url):
    http=urllib.request.build_opener(urllib.request.ProxyHandler({}))
    for _ in range(50):
        try:
            with http.open(url,timeout=2) as response:
                if json.load(response).get("status")=="UP":
                    return
        except OSError:
            pass
        time.sleep(.4)
    raise RuntimeError(f"not healthy: {url}")


if not JAR.is_file():
    raise SystemExit("Build backend first: cd backend && ./mvnw verify")
try:
    subprocess.run(compose+["up","-d","--wait","--wait-timeout","60","mysql"],cwd=ROOT,env=env,check=True)
    with (ROOT/"infra/mysql/ensure-app-user.sh").open() as script:
        subprocess.run(compose+["exec","-T","mysql","bash","-s"],cwd=ROOT,env=env,stdin=script,check=True)
    old_m3 = options.upgrade_from_m3.resolve() if options.upgrade_from_m3 else JAR
    first_jar = options.upgrade_from.resolve() if options.upgrade_from else old_m3
    with process("java",["java","-jar",str(first_jar)],ROOT):
        await_health(base+"/actuator/health/readiness")
        a02(options.browser)
        a03()
        a04()
        if options.m2 or options.m3 or options.m4:
            import m2
            m2.run(Client, expect, sql, env, options.browser, process, await_health, web_port, ROOT)
    if options.m3 or options.m4:
        import m3
        before = m3.snapshot(sql)
        if options.upgrade_from:
            with process("java V4 first start",["java","-jar",str(JAR)],ROOT):
                await_health(base+"/actuator/health/readiness")
                first_tasks = sql("SELECT task_no,reservation_id,status FROM pick_task ORDER BY reservation_id")
                assert first_tasks and before == m3.snapshot(sql)
        with process("java M3",["java","-jar",str(old_m3)],ROOT):
            await_health(base+"/actuator/health/readiness")
            assert before == m3.snapshot(sql), "migration changed balances or reservations"
            if options.upgrade_from:
                assert first_tasks == sql("SELECT task_no,reservation_id,status FROM pick_task ORDER BY reservation_id")
                print("V3→V4: balance/reservation facts unchanged, task IDs stable across restart")
            m3.run(Client, expect, sql, env, options.browser, process, await_health, web_port, ROOT)
            if options.m4:
                import m4
                historical=m4.before_upgrade(Client,expect,sql,bool(options.upgrade_from_m3))
    if options.m4:
        services=["mq-broker"] if options.native_redis else ["mq-broker","redis"]
        subprocess.run(compose+["up","-d","--wait","--wait-timeout","75",*services],cwd=ROOT,env=env,check=True)
        subprocess.run(compose+["stop","mq-broker"],cwd=ROOT,env=env,check=True)
        with ExitStack() as stack:
            if options.native_redis:
                stack.enter_context(process("native Redis",["redis-server","--bind","127.0.0.1","--port",str(redis_port),
                    "--save","","--appendonly","no"],ROOT))
                cli_env={k:v for k,v in env.items() if k!="REDISCLI_AUTH"}
                m4.eventually(lambda:subprocess.run(["redis-cli","-p",str(redis_port),"ping"],env=cli_env,
                    capture_output=True).stdout==b"PONG\n")
            with tempfile.TemporaryDirectory() as temp:
                log_path=Path(temp)/"m4-workers.log"
                with process("java M4",["java",f"-Drocketmq.log.root={temp}/rocketmq","-jar",str(JAR)],ROOT,
                        dict(env,HF_EVENTS_ENABLED="true",HF_OPERATIONS_CACHE="true"),log_path):
                    await_health(base+"/actuator/health/readiness")
                    m4.run(Client,expect,sql,env,options.browser,process,await_health,web_port,ROOT,JAR,
                           compose,project,restore_port,restore_api,historical,options.native_redis,log_path)
finally:
    # This name is generated per run; never remove the main project's volume.
    subprocess.run(compose+["down","--volumes"],cwd=ROOT,env=env,check=True)
