#!/usr/bin/env python3
"""Fills a Be PRO backend with 4 realistic weeks of data for a demo, then runs the AI digest.

    python3 scripts/demo_seed.py --url https://bepro-xxxx.run.app --secret <pairing secret>

The story the data tells: on nights with late Instagram/YouTube scrolling, sleep is short, the next
day has fewer steps, less focus and a lower mood. Weekends are calmer. The last three nights are
short, so the "under 5 h three nights in a row" alert shows on the Today screen.

Uses the same event shapes the Android collectors send. Deterministic (fixed random seed), and
re-running it updates the same rows instead of duplicating them. Standard library only.
DEMO USE ONLY: never point this at a backend holding real data.
"""
import argparse
import json
import random
import sys
import urllib.error
import urllib.request
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

APPS = {
    "com.instagram.android": "Instagram",
    "com.google.android.youtube": "YouTube",
    "com.whatsapp": "WhatsApp",
    "com.android.chrome": "Chrome",
    "com.google.android.gm": "Gmail",
    "com.Slack": "Slack",
    "com.spotify.music": "Spotify",
    "com.linkedin.android": "LinkedIn",
}
MOOD_NOTES = {
    1: ["exhausted, stayed up scrolling", "headache, barely slept"],
    2: ["tired after late Instagram", "groggy all morning", "low energy"],
    3: ["okay day", "meetings drained me", "average"],
    4: ["good focus block", "nice walk in the evening", "productive"],
    5: ["great run + early night", "felt sharp all day"],
}


def http(method, url, token=None, body=None, timeout=120):
    req = urllib.request.Request(url, method=method, data=json.dumps(body).encode() if body is not None else None)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            text = r.read().decode()
            return r.status, json.loads(text) if text else None
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


class Builder:
    def __init__(self, zone):
        self.zone = zone
        self.events = []

    def ev(self, cat, sub, value, unit, start, end, source, meta=None):
        self.events.append({
            "category": cat, "subCategory": sub, "value": round(value, 2), "unit": unit,
            "startedAt": start.isoformat(), "endedAt": end.isoformat() if end else None,
            "source": source, "metadata": meta or {},
        })

    def screen(self, start, minutes, apps):
        """One screen-on session, split across the given apps, plus the unlock that started it."""
        end = start + timedelta(minutes=minutes)
        self.ev("screen", "session", minutes, "minutes", start, end, "usage_stats")
        self.ev("screen", "unlock", 1, "count", start, None, "usage_stats")
        weights = [random.uniform(0.6, 1.4) * w for _, w in apps]
        total = sum(weights)
        cursor = start
        for (pkg, _), w in zip(apps, weights):
            m = minutes * w / total
            if m < 1:
                continue
            self.ev("usage", pkg, m, "minutes", cursor, cursor + timedelta(minutes=m), "usage_stats",
                    {"label": APPS[pkg]})
            cursor += timedelta(minutes=m)


def build(zone, days, now):
    random.seed(20261102)
    b = Builder(zone)
    today = now.date()
    first = today - timedelta(days=days - 1)

    def at(d, h, m=0):
        return datetime(d.year, d.month, d.day, h, m, tzinfo=zone)

    # Decide each night first (the night *before* day d), so the next day can react to it.
    late = {}
    for i in range(days + 1):
        d = first + timedelta(days=i - 1)
        weekend_night = d.weekday() in (4, 5)  # Fri/Sat nights
        days_left = (today - d).days
        if 1 <= days_left <= 3:     # last three nights: the streak that triggers the alert
            late[d] = "very"
        elif weekend_night:
            late[d] = random.choice(["no", "some"])
        else:
            late[d] = random.choices(["no", "some", "very"], weights=[45, 30, 25])[0]

    # The evening before the first day, so day one has an inferred sleep too.
    eve = first - timedelta(days=1)
    b.screen(at(eve, 22, 50), 30, [("com.instagram.android", 2), ("com.whatsapp", 1)])

    for i in range(days):
        d = first + timedelta(days=i)
        prev = d - timedelta(days=1)
        weekend = d.weekday() >= 5
        night = late[prev]

        # Wake-up (first screen-on of the day ends the inferred sleep).
        wake = at(d, 8, random.randint(20, 50)) if weekend else at(d, 6, random.randint(45, 59)) + timedelta(
            minutes=random.randint(0, 25))
        if night == "very":
            wake = at(d, 6, random.randint(15, 35))

        sessions = [
            (wake, random.randint(10, 18), [("com.whatsapp", 2), ("com.instagram.android", 1), ("com.google.android.gm", 1)]),
            (at(d, 9, random.randint(0, 20)), random.randint(15, 25), [("com.spotify.music", 1), ("com.linkedin.android", 1), ("com.whatsapp", 1)]),
            (at(d, 11, random.randint(0, 30)), random.randint(8, 15), [("com.Slack", 2), ("com.android.chrome", 1)]),
            (at(d, 13, random.randint(10, 30)), random.randint(25, 40), [("com.instagram.android", 2), ("com.google.android.youtube", 1), ("com.whatsapp", 1)]),
            (at(d, 16, random.randint(0, 30)), random.randint(10, 20), [("com.Slack", 1), ("com.android.chrome", 1), ("com.google.android.gm", 1)]),
            (at(d, 19, random.randint(15, 45)), random.randint(25, 45), [("com.whatsapp", 1), ("com.google.android.youtube", 1), ("com.android.chrome", 1)]),
        ]
        if weekend:
            sessions[2:5] = [(at(d, 12, 0), random.randint(30, 50), [("com.google.android.youtube", 2), ("com.instagram.android", 1)])]

        # Tonight's wind-down; ends at bedtime (the screen-off gap until tomorrow's wake is the sleep).
        tonight = late[d]
        bed_min = {"no": random.randint(22 * 60 + 40, 23 * 60 + 25),
                   "some": random.randint(23 * 60 + 40, 24 * 60 + 20),
                   "very": random.randint(25 * 60 + 40, 26 * 60 + 15)}[tonight]
        if weekend and tonight == "no":
            bed_min += 40
        bedtime = at(d, 0) + timedelta(minutes=bed_min)
        scroll = {"no": random.randint(20, 35), "some": random.randint(55, 80), "very": random.randint(110, 150)}[tonight]
        sessions.append((bedtime - timedelta(minutes=scroll), scroll,
                         [("com.instagram.android", 5), ("com.google.android.youtube", 2), ("com.whatsapp", 1)]))

        for start, minutes, apps in sessions:
            if start + timedelta(minutes=minutes) <= now:
                b.screen(start, minutes, apps)
        # Quick glances: an unlock and a minute in one app.
        glance_apps = ["com.whatsapp", "com.whatsapp", "com.instagram.android", "com.google.android.gm", "com.Slack"]
        for _ in range(random.randint(30, 45) if night == "very" else random.randint(15, 28)):
            t = at(d, random.randint(8, 21), random.randint(0, 59))
            if t < now:
                b.screen(t, random.choice([1, 1, 2]), [(random.choice(glance_apps), 1)])

        # Movement: a short night means a lazier day.
        tired = night == "very"
        step_target = random.randint(3000, 4800) if tired else random.randint(6500, 9500)
        if weekend and not tired:
            step_target = random.randint(8000, 12500)
        hours = list(range(8, 21))
        hw = [1 if h not in (9, 13, 18) else 3 for h in hours]
        for h, w in zip(hours, hw):
            start = at(d, h)
            if start + timedelta(hours=1) > now:
                continue
            steps = step_target * w / sum(hw) * random.uniform(0.7, 1.3)
            b.ev("steps", "", round(steps), "steps", start, start + timedelta(hours=1), "sensor")
        walk_start = at(d, 18, random.randint(0, 30))
        if walk_start + timedelta(minutes=40) <= now:
            b.ev("activity", "walking", random.randint(12, 20) if tired else random.randint(25, 45), "minutes",
                 walk_start, walk_start + timedelta(minutes=30), "activity")
        if d.weekday() in (1, 5) and not tired:
            run = at(d, 7, 30) if d.weekday() == 1 else at(d, 9, 30)
            if run + timedelta(minutes=30) <= now:
                b.ev("activity", "running", random.randint(24, 32), "minutes", run, run + timedelta(minutes=28), "activity")

        # Focus: calendar deep-work blocks on weekdays, plus timer sessions (fewer when tired).
        if not weekend:
            if random.random() < (0.35 if tired else 0.8):
                s = at(d, 10, 0)
                if s + timedelta(minutes=90) <= now:
                    b.ev("focus", "calendar", 90, "minutes", s, s + timedelta(minutes=90), "calendar", {"keyword": "deep work"})
            for slot in ([at(d, 15, 0)] if tired else [at(d, 14, 30), at(d, 17, 0)]):
                planned = random.choice([25, 45])
                done = planned if not tired or random.random() < 0.5 else random.randint(8, planned - 5)
                if slot + timedelta(minutes=done) <= now:
                    b.ev("focus", "timer", done, "minutes", slot, slot + timedelta(minutes=done), "manual",
                         {"plannedMinutes": planned})

        # Mood at 21:30.
        score = 2 if tired else (4 if night == "no" else 3)
        if weekend and not tired:
            score = random.choice([4, 5])
        score = max(1, min(5, score + random.choice([0, 0, 0, -1, 1])))
        m = at(d, 21, 30)
        if m <= now:
            b.ev("mood", "", score, "score", m, None, "manual", {"note": random.choice(MOOD_NOTES[score])})
    return b.events, first, today


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--url", required=True)
    ap.add_argument("--secret", required=True)
    ap.add_argument("--days", type=int, default=28)
    ap.add_argument("--zone", default="Asia/Kolkata")
    a = ap.parse_args()
    url = a.url.rstrip("/")
    zone = ZoneInfo(a.zone)

    code, body = http("POST", f"{url}/v1/auth/token", body={"deviceId": "demo-seed", "secret": a.secret})
    if code != 200:
        sys.exit(f"login failed: {code} {body}")
    token = body["accessToken"]

    events, first, today = build(zone, a.days, datetime.now(zone))
    print(f"Uploading {len(events)} events for {first} … {today}")
    for i in range(0, len(events), 1000):
        code, body = http("POST", f"{url}/v1/events/batch", token, {"events": events[i:i + 1000]})
        if code != 200:
            sys.exit(f"upload failed at batch {i // 1000}: {code} {body}")
        print(f"  batch {i // 1000 + 1}: {body}")

    code, days = http("GET", f"{url}/v1/summary/daily?from={first}&to={today}", token)
    print("\nDay         sleep  screen  steps  focus  mood")
    for d in days:
        print(f"{d['date']}  {str(d.get('sleepHours') or '-'):>5}  {d.get('screenMinutes', 0):>5}m  "
              f"{str(d.get('steps') or '-'):>5}  {d.get('focusMinutes', 0):>4}m  {d.get('moodScore') or '-'}")

    print("\nRunning the weekly AI digest …")
    code, digest = http("POST", f"{url}/v1/digest/run?force=true", token)
    print(json.dumps(digest, indent=2) if code == 200 else f"digest failed: {code} {digest}")
    code, latest = http("GET", f"{url}/v1/insights/latest", token)
    print(f"\nActive cards: {[(i['kind'], i['pattern'][:70]) for i in latest]}" if code == 200 else latest)


if __name__ == "__main__":
    main()
