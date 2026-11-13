#!/usr/bin/env python3
"""End-to-end health check for the local Be PRO setup.

    python3 scripts/check.py            # everything
    python3 scripts/check.py --no-ai    # skip the Gemini call (free, offline)

Checks:
  1. Docker: Postgres + backend running, /v1/health ok.
  2. Real backend: auth rejects a wrong secret, accepts the real one, read-only endpoints answer.
  3. Self-test backend: a second backend container on :18080 with user "bepro-selftest". It gets a
     synthetic week of data and runs the full flow (upload, dedupe, aggregates, timeline, digest via
     Vertex AI, insights, CSV). Its rows are deleted afterwards, so your real data is never touched.
  4. Vertex AI: gcloud credentials present, Gemini answers.
  5. Emulator/phone: device connected, app installed and running, adb reverse set, no crashes,
     app paired and syncing.

Only uses the Python standard library.
"""
import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ADB = os.path.expanduser("~/Android/Sdk/platform-tools/adb")
REAL = "http://localhost:8080"
SELFTEST_PORT = 18080
SELFTEST = f"http://localhost:{SELFTEST_PORT}"
SELFTEST_USER = "bepro-selftest"
SELFTEST_CONTAINER = "bepro-selftest"
DEVICE_ID = "bepro-check-script"
PKG = "app.bepro"

GREEN, RED, YELLOW, DIM, RESET = "\033[32m", "\033[31m", "\033[33m", "\033[2m", "\033[0m"
results = {"pass": 0, "fail": 0, "warn": 0}


def ok(msg):
    results["pass"] += 1
    print(f"  {GREEN}✔{RESET} {msg}")


def fail(msg, detail=None):
    results["fail"] += 1
    print(f"  {RED}✘ {msg}{RESET}")
    if detail:
        print(f"    {DIM}{str(detail)[:600]}{RESET}")


def warn(msg):
    results["warn"] += 1
    print(f"  {YELLOW}! {msg}{RESET}")


def check(cond, msg, detail=None):
    (ok if cond else lambda m: fail(m, detail))(msg)
    return cond


def section(title):
    print(f"\n{title}")


def load_env():
    env = {}
    path = os.path.join(ROOT, ".env")
    if not os.path.exists(path):
        return env
    for line in open(path):
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            env[k] = v
    return env


def http(method, url, token=None, body=None, raw=False, timeout=30):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    if data is not None:
        req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            text = r.read().decode()
            return r.status, (text if raw else (json.loads(text) if text else None))
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()
    except (urllib.error.URLError, ConnectionError, TimeoutError) as e:
        return 0, str(e)


def sh(*args, timeout=60):
    try:
        p = subprocess.run(args, capture_output=True, text=True, timeout=timeout, cwd=ROOT)
        return p.returncode, (p.stdout + p.stderr).strip()
    except (FileNotFoundError, subprocess.TimeoutExpired) as e:
        return 1, str(e)


def login(base, secret):
    code, body = http("POST", f"{base}/v1/auth/token", body={"deviceId": DEVICE_ID, "secret": secret})
    return (body.get("accessToken") if code == 200 and isinstance(body, dict) else None), code, body


def wait_healthy(base, seconds):
    deadline = time.time() + seconds
    while time.time() < deadline:
        code, body = http("GET", f"{base}/v1/health", timeout=3)
        if code == 200:
            return True
        time.sleep(2)
    return False


# ---------------------------------------------------------------- 1. docker

def check_docker():
    section("1. Docker")
    code, out = sh("docker", "compose", "ps", "--format", "{{.Service}} {{.State}} {{.Health}}")
    if not check(code == 0, "docker compose is available", out):
        return False
    states = dict(line.split(" ", 1) for line in out.splitlines() if " " in line)
    check(states.get("db", "").startswith("running"), f"Postgres container running ({states.get('db', 'missing')})")
    check(states.get("backend", "").startswith("running"), f"backend container running ({states.get('backend', 'missing')})")
    code, body = http("GET", f"{REAL}/v1/health")
    return check(code == 200 and isinstance(body, dict) and body.get("status") == "ok", "GET /v1/health → ok", body)


# ---------------------------------------------------------------- 2. real backend (read-only)

def check_real_backend(env):
    section("2. Your backend (read-only)")
    _, code, _ = login(REAL, "definitely-wrong")
    check(code == 401, "wrong pairing secret is rejected (401)", code)
    code, _ = http("GET", f"{REAL}/v1/summary/today")
    check(code == 401, "requests without a token are rejected (401)", code)

    token, code, body = login(REAL, env.get("BEPRO_DEVICE_SECRET", ""))
    if not check(token is not None, "pairing secret from .env gets a token", body):
        return None
    for path in ["/v1/summary/today", "/v1/summary/week", "/v1/timeline", "/v1/insights/latest"]:
        code, body = http("GET", REAL + path, token)
        check(code == 200, f"GET {path} → 200", body)
    code, today = http("GET", f"{REAL}/v1/summary/today", token)
    # The check script registered itself as a device; remove that row again.
    sh("docker", "compose", "exec", "-T", "db", "psql", "-U", "bepro", "-d", "bepro", "-qc",
       f"DELETE FROM devices WHERE device_id = '{DEVICE_ID}'")
    return today if code == 200 else None


# ---------------------------------------------------------------- 3. self-test backend

def synthetic_week(zone, end):
    """7 complete days ending at `end`: sleep, screen sessions, unlocks, app usage, steps, mood, focus."""
    def at(d, hhmm):
        h, m = map(int, hhmm.split(":"))
        return datetime(d.year, d.month, d.day, h, m, tzinfo=zone)

    def iso(t):
        return t.isoformat()

    def ev(cat, sub, value, unit, start, end_, source, meta=None):
        return {"category": cat, "subCategory": sub, "value": value, "unit": unit, "startedAt": iso(start),
                "endedAt": iso(end_) if end_ else None, "source": source, "metadata": meta or {}}

    events = []
    for i in range(7):
        d = end - timedelta(days=6 - i)
        late = i % 2 == 0  # alternate late and early nights so the AI has a pattern to find
        bed = at(d - timedelta(days=1), "01:15" if late else "23:00") + (timedelta(days=1) if late else timedelta())
        wake = at(d, "07:30")
        events.append(ev("sleep", "", round((wake - bed).total_seconds() / 3600, 2), "hours", bed, wake, "manual"))
        insta = 120 if late else 35
        events += [
            ev("screen", "session", 20, "minutes", at(d, "07:35"), at(d, "07:55"), "usage_stats"),
            ev("screen", "session", insta, "minutes", at(d, "21:00"), at(d, "21:00") + timedelta(minutes=insta), "usage_stats"),
            ev("usage", "com.instagram.android", insta, "minutes", at(d, "21:00"),
               at(d, "21:00") + timedelta(minutes=insta), "usage_stats", {"label": "Instagram"}),
            ev("usage", "com.android.chrome", 20, "minutes", at(d, "07:35"), at(d, "07:55"), "usage_stats",
               {"label": "Chrome"}),
            ev("steps", "", 3000 if late else 7500, "steps", at(d, "12:00"), at(d, "13:00"), "sensor"),
            ev("mood", "", 2 if late else 4, "score", at(d, "20:00"), None, "manual"),
            ev("focus", "timer", 25, "minutes", at(d, "15:00"), at(d, "15:25"), "manual", {"plannedMinutes": 25}),
        ]
        for t in ["08:00", "11:00", "14:00", "18:00", "21:00"]:
            events.append(ev("screen", "unlock", 1, "count", at(d, t), None, "usage_stats"))
    return events


def selftest_cleanup():
    sh("docker", "rm", "-f", SELFTEST_CONTAINER)
    sh("docker", "compose", "exec", "-T", "db", "psql", "-U", "bepro", "-d", "bepro", "-qc",
       "DELETE FROM events WHERE user_id = '{0}'; DELETE FROM daily_aggregates WHERE user_id = '{0}';"
       "DELETE FROM insights WHERE user_id = '{0}'; DELETE FROM devices WHERE user_id = '{0}';".format(SELFTEST_USER))


def check_selftest(env, use_ai):
    section(f"3. Full flow on a throw-away user ({SELFTEST_USER}, port {SELFTEST_PORT})")
    selftest_cleanup()
    args = ["docker", "compose", "run", "-d", "--rm", "--no-deps", "--name", SELFTEST_CONTAINER,
            "-p", f"{SELFTEST_PORT}:8080", "-e", f"BEPRO_USER_ID={SELFTEST_USER}", "-e", "BEPRO_DIGEST_SCHEDULER=false"]
    if not use_ai:
        args += ["-e", "BEPRO_AI_PROVIDER=rules"]
    code, out = sh(*args, "backend", timeout=120)
    if not check(code == 0, "self-test container started", out):
        return
    try:
        if not check(wait_healthy(SELFTEST, 90), "self-test backend became healthy"):
            print(f"    {DIM}{sh('docker', 'logs', '--tail', '20', SELFTEST_CONTAINER)[1]}{RESET}")
            return
        token, code, body = login(SELFTEST, env.get("BEPRO_DEVICE_SECRET", ""))
        if not check(token is not None, "login", body):
            return

        zone = ZoneInfo(env.get("BEPRO_ZONE", "Asia/Kolkata"))
        yesterday = datetime.now(zone).date() - timedelta(days=1)
        first = yesterday - timedelta(days=6)
        events = synthetic_week(zone, yesterday)

        code, body = http("POST", f"{SELFTEST}/v1/events/batch", token, {"events": events})
        check(code == 200 and body.get("accepted") == len(events), f"upload {len(events)} events", body)
        code, body = http("POST", f"{SELFTEST}/v1/events/batch", token, {"events": events})
        check(code == 200, "re-upload of the same events accepted (idempotent)", body)
        code, out = sh("docker", "compose", "exec", "-T", "db", "psql", "-U", "bepro", "-d", "bepro", "-tAc",
                       f"SELECT count(*) FROM events WHERE user_id = '{SELFTEST_USER}'")
        check(out.strip() == str(len(events)), f"no duplicates in the database ({out.strip()} rows)", out)

        bad = dict(events[0], category="weather")
        code, _ = http("POST", f"{SELFTEST}/v1/events/batch", token, {"events": [bad]})
        check(code == 400, "invalid event is rejected (400)", code)

        code, days = http("GET", f"{SELFTEST}/v1/summary/daily?from={first}&to={yesterday}", token)
        if check(code == 200 and isinstance(days, list) and len(days) == 7, "daily aggregates: 7 days", days):
            check(all(d.get("sleepHours") for d in days), "sleep present every day",
                  [d.get("sleepHours") for d in days])
            check(all((d.get("usageMinutes") or 0) > 0 for d in days), "app usage present every day")
            check(days[0].get("topApp") in ("com.instagram.android", "Instagram"), f"top app = {days[0].get('topApp')}",
                  days[0])

        code, tl = http("GET", f"{SELFTEST}/v1/timeline?date={yesterday}", token)
        check(code == 200 and len(tl.get("hours", [])) == 24, "timeline has 24 hourly buckets", tl)

        code, week = http("GET", f"{SELFTEST}/v1/summary/week?end={yesterday}", token)
        check(code == 200 and week.get("completeDays") == 7, f"week summary: {week.get('completeDays')}/7 complete days",
              week)

        label = "Vertex AI (Gemini)" if use_ai else "rule-based generator"
        t0 = time.time()
        code, digest = http("POST", f"{SELFTEST}/v1/digest/run?force=true", token, timeout=120)
        took = time.time() - t0
        if check(code == 200 and digest.get("status") == "generated",
                 f"weekly digest generated by {label} in {took:.1f}s", digest):
            for ins in digest.get("insights", []):
                print(f"    {DIM}[{ins.get('category')}/{ins.get('confidence')}] {ins.get('pattern')}{RESET}")
                print(f"    {DIM}  → {ins.get('action')}{RESET}")
        elif use_ai:
            print(f"    {DIM}{sh('docker', 'logs', '--tail', '30', SELFTEST_CONTAINER)[1][-1500:]}{RESET}")

        code, latest = http("GET", f"{SELFTEST}/v1/insights/latest", token)
        weekly = [i for i in latest if i.get("kind") == "weekly"] if code == 200 else []
        if check(len(weekly) >= 1, f"GET /insights/latest returns {len(weekly)} weekly card(s)", latest):
            code, body = http("PATCH", f"{SELFTEST}/v1/insights/{weekly[0]['id']}", token, {"status": "dismissed"})
            check(code in (200, 204), "dismissing an insight works", body)

        code, csv = http("GET", f"{SELFTEST}/v1/export/events.csv?from={first}T00:00:00Z&to={yesterday + timedelta(days=1)}T23:59:59Z", token, raw=True)
        check(code == 200 and csv.count("\n") >= len(events), f"CSV export ({csv.count(chr(10))} lines)",
              csv[:200] if isinstance(csv, str) else csv)
    finally:
        selftest_cleanup()
        ok("self-test container and its data removed")


# ---------------------------------------------------------------- 4. vertex

def check_vertex(env):
    section("4. Vertex AI")
    provider = env.get("BEPRO_AI_PROVIDER")
    check(provider == "vertex", f"BEPRO_AI_PROVIDER={provider}")
    project, model = env.get("GOOGLE_CLOUD_PROJECT"), env.get("BEPRO_VERTEX_MODEL", "gemini-3.5-flash")
    location = env.get("BEPRO_VERTEX_LOCATION", "global")
    check(bool(project), f"GOOGLE_CLOUD_PROJECT={project}")
    adc = os.path.expanduser("~/.config/gcloud/application_default_credentials.json")
    if not check(os.path.exists(adc), "gcloud application-default credentials exist",
                 "run: gcloud auth application-default login"):
        return
    code, out = sh("docker", "compose", "exec", "-T", "backend", "sh", "-c", "cat /secrets/adc.json > /dev/null")
    check(code == 0, "credentials are mounted into the backend container", "see docker-compose.override.yml")
    code, tok = sh("gcloud", "auth", "application-default", "print-access-token")
    if not check(code == 0, "access token can be minted", tok):
        return
    host = "aiplatform.googleapis.com" if location == "global" else f"{location}-aiplatform.googleapis.com"
    url = f"https://{host}/v1/projects/{project}/locations/{location}/publishers/google/models/{model}:generateContent"
    req = urllib.request.Request(url, method="POST", data=json.dumps(
        {"contents": [{"role": "user", "parts": [{"text": "Reply with just the word: ok"}]}]}).encode())
    req.add_header("Authorization", f"Bearer {tok.splitlines()[-1]}")
    req.add_header("x-goog-user-project", project)
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            body = json.loads(r.read())
        text = body["candidates"][0]["content"]["parts"][0].get("text", "").strip()
        check(bool(text), f"{model} answered: {text!r}")
    except urllib.error.HTTPError as e:
        fail(f"{model} call failed ({e.code})", e.read().decode())
    except Exception as e:  # noqa: BLE001
        fail(f"{model} call failed", e)


# ---------------------------------------------------------------- 5. emulator / phone

def check_device(today):
    section("5. Emulator / phone")
    if not os.path.exists(ADB):
        warn(f"adb not found at {ADB}; skipping")
        return
    code, out = sh(ADB, "devices")
    devices = [l.split()[0] for l in out.splitlines()[1:] if l.strip().endswith("device")]
    if not devices:
        warn("no emulator or phone connected; skipping app checks")
        return
    serial = devices[0]
    ok(f"device connected: {serial}")

    def adb(*a):
        return sh(ADB, "-s", serial, *a)

    _, ver = adb("shell", "getprop", "ro.build.version.release")
    ok(f"Android {ver}")
    code, out = adb("shell", "pm", "path", PKG)
    if not check(code == 0 and "package:" in out, f"{PKG} installed"):
        return
    _, out = adb("shell", "dumpsys", "package", PKG)
    vline = next((l.strip() for l in out.splitlines() if "versionName" in l), "?")
    ok(f"version {vline.split('=')[-1]}")
    _, pid = adb("shell", "pidof", PKG)
    if pid.strip():
        ok(f"app process running (pid {pid.strip()})")
    else:
        warn("app is not running right now (fine if you closed it; WorkManager still runs)")
    _, rev = adb("reverse", "--list")
    check("tcp:8080" in rev, "adb reverse tcp:8080 is set (app → backend)",
          f"run: {ADB} reverse tcp:8080 tcp:8080")
    _, crashes = adb("logcat", "-d", "-b", "crash")
    app_crashes = [l for l in crashes.splitlines() if PKG in l]
    check(not app_crashes, "no app crashes in logcat", "\n".join(app_crashes[-5:]))
    _, perm = adb("shell", "appops", "get", PKG, "GET_USAGE_STATS")
    check("allow" in perm, "Usage access granted", perm or "open the app → Setup → Usage access")

    last = (today or {}).get("lastSyncAt")
    if last:
        age = datetime.now().astimezone() - datetime.fromisoformat(last)
        mins = age.total_seconds() / 60
        if mins < 60:
            ok(f"app paired and synced {mins:.0f} min ago")
        else:
            warn(f"last sync was {mins / 60:.1f} h ago (expected every ~15 min)")
    else:
        warn("backend has no sync from the app yet (pair it in Setup, then 'Run a sync now')")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--no-ai", action="store_true", help="use the rule-based generator in the self-test")
    a = ap.parse_args()

    env = load_env()
    if not env:
        print(f"{RED}No .env in {ROOT}. Copy .env.example to .env first.{RESET}")
        return 2
    print(f"Be PRO check · {datetime.now():%Y-%m-%d %H:%M}")
    today = None
    if check_docker():
        today = check_real_backend(env)
        check_selftest(env, use_ai=not a.no_ai)
    if not a.no_ai:
        check_vertex(env)
    check_device(today)

    colour = GREEN if results["fail"] == 0 else RED
    print(f"\n{colour}{results['pass']} passed, {results['fail']} failed{RESET}, {results['warn']} warnings")
    return 0 if results["fail"] == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
