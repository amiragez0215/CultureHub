"""Check real Vue, SSE and the pending proposal path with a loopback Chrome instance."""
import argparse
import json
import subprocess
import time
import urllib.request
from pathlib import Path

import websocket


def wait_for(operation, seconds=30):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        result = operation()
        if result:
            return result
        time.sleep(.2)
    raise AssertionError("Browser did not reach expected state")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--chrome", required=True)
    parser.add_argument("--url", default="http://127.0.0.1:15190")
    parser.add_argument("--output", type=Path, required=True)
    options = parser.parse_args()
    profile = Path(".tools") / f"guide-round2-browser-{int(time.time())}"
    profile.mkdir(parents=True, exist_ok=False)
    browser = subprocess.Popen([options.chrome, "--headless=new", "--no-first-run",
                                "--no-default-browser-check", f"--user-data-dir={profile.resolve()}",
                                "--remote-debugging-address=127.0.0.1",
                                "--remote-debugging-port=18102", options.url],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                               creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    try:
        def target():
            try:
                with urllib.request.urlopen("http://127.0.0.1:18102/json/list", timeout=2) as r:
                    return next((item["webSocketDebuggerUrl"] for item in json.load(r)
                                 if item["type"] == "page" and item["url"].startswith(options.url)), None)
            except OSError:
                return None

        session = websocket.create_connection(wait_for(target), timeout=8, suppress_origin=True)
        try:
            sequence = 0

            def js(expression):
                nonlocal sequence
                sequence += 1
                session.send(json.dumps({"id": sequence, "method": "Runtime.evaluate",
                                         "params": {"expression": expression, "returnByValue": True}}))
                while True:
                    result = json.loads(session.recv())
                    if result.get("id") == sequence:
                        if "exceptionDetails" in result.get("result", {}):
                            raise AssertionError("Page expression failed")
                        return result.get("result", {}).get("result", {}).get("value")

            def contains(selector, value):
                return f"[...document.querySelectorAll({json.dumps(selector)})].find(e=>e.textContent.includes({json.dumps(value)}))"

            def click(value, selector="button"):
                expr = contains(selector, value)
                wait_for(lambda: js(f"(()=>{{const e={expr};return !!e&&!e.disabled}})()"))
                js(f"{expr}.click()")

            def fill(selector, value):
                js(f"""(()=>{{const e=document.querySelector({json.dumps(selector)});
                     e.value={json.dumps(value)};e.dispatchEvent(new Event('input',{{bubbles:true}}));}})()""")

            click("登录 / 注册")
            wait_for(lambda: js("!!document.querySelector('input[autocomplete=username]')"))
            fill("input[autocomplete=username]", f"r2_browser_{int(time.time())}")
            fill("input[autocomplete=current-password]", "123")
            js("""(()=>{
                const fetcher=window.fetch;
                window.acceptanceRequests=[];
                window.fetch=(...args)=>fetcher(...args).then(response=>{
                  window.acceptanceRequests.push({status:response.status,path:new URL(response.url).pathname});
                  return response;
                });
            })()""")
            click("注册并登录")
            try:
                wait_for(lambda: js("!!sessionStorage.getItem('token')"))
            except AssertionError as failure:
                raise AssertionError(f"Login did not complete; HTTP {js('window.acceptanceRequests')}") from failure
            click("智能导览")
            wait_for(lambda: js("!!document.querySelector('.chat-panel textarea')"))
            fill(".chat-panel textarea", "请在市文化博物馆推荐一个活动，时间不限。")
            click("发送问题")
            wait_for(lambda: js("!!document.querySelector('.chat-panel [role=status]')"))
            wait_for(lambda: js("document.querySelectorAll('.guide-session-card').length >= 2"), 100)
            assert js("document.querySelector('.chat-panel').textContent.includes('尚未预约')")
            click("选择此场次")
            wait_for(lambda: js("!!document.getElementById('guide-proposal')"))
            assert js("document.getElementById('guide-proposal').textContent.includes('未提交')")
            click("取消此方案")
            wait_for(lambda: js("document.getElementById('guide-proposal')?.textContent.includes('没有提交新的预约')"))
            result = {"registeredViaVue": True, "sseProgressObserved": True,
                      "verifiedPlanAndCatalogCardRendered": True, "pendingProposalCreated": True,
                      "proposalRejectedWithoutBooking": True}
            options.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
            print(json.dumps(result))
        finally:
            session.close()
    finally:
        browser.terminate()
        try:
            browser.wait(12)
        except subprocess.TimeoutExpired:
            browser.kill()


if __name__ == "__main__":
    main()
