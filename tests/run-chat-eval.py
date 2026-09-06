#!/usr/bin/env python3
"""Run tests/agent-chat-eval.json against a live agent-service.

agent-service does not publish its port, so this normally runs inside the
compose network:

  docker run --rm --network ticketing-platform_ticketing-net \
      -v "$PWD/tests:/t" python:3.12-slim \
      python /t/run-chat-eval.py

Override the target with AGENT_URL when it is reachable some other way.
Exit status is the number of failing cases, capped at 100.
"""
import json, os, pathlib, random, sys, urllib.error, urllib.request

BASE = os.environ.get("AGENT_URL", "http://agent-service:8092")
CASES = json.loads((pathlib.Path(__file__).parent / "agent-chat-eval.json").read_text())["cases"]


def post(session_id, message):
    body = json.dumps({"sessionId": session_id, "message": message}).encode()
    req = urllib.request.Request(f"{BASE}/api/agent/chat", data=body,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as r:
        return json.loads(r.read()).get("data") or {}


def verdict(check, reply):
    """Returns (passed, what we actually saw)."""
    cities = [h.get("venueCity") for h in (reply.get("hits") or [])]
    if "allCity" in check:
        want = check["allCity"]
        return bool(cities) and all(c == want for c in cities), fmt(cities)
    if "multiCity" in check:
        return len({c for c in cities if c}) >= 2, fmt(cities)
    if "stage" in check:
        return reply.get("stage") == check["stage"], str(reply.get("stage"))
    raise ValueError(f"unknown check {check}")


def fmt(cities):
    if not cities:
        return "(khong co ket qua)"
    seen = sorted({c or "?" for c in cities})
    return f"{' · '.join(seen)}  (n={len(cities)})"


def main():
    failed, rows = [], []
    for case in CASES:
        sid = f"chat-eval-{case['id']}-{random.randint(1000, 9999)}"
        reply = {}
        try:
            for turn in case["turns"]:
                reply = post(sid, turn)
        except (urllib.error.URLError, TimeoutError) as e:
            rows.append((case, False, f"ERROR {e}"))
            failed.append(case["id"])
            continue
        ok, saw = verdict(case["check"], reply)
        rows.append((case, ok, saw))
        if not ok:
            failed.append(case["id"])

    width = max(len(c["id"]) for c in CASES)
    for case, ok, saw in rows:
        print(f"{'PASS' if ok else 'FAIL'}  {case['id']:<{width}}  {case['group']:<9}  {saw}")
        if not ok:
            print(f"{'':<{width + 24}}mong doi: {json.dumps(case['check'])}")
    print(f"\n{len(CASES) - len(failed)}/{len(CASES)}")
    return min(len(failed), 100)


if __name__ == "__main__":
    sys.exit(main())
