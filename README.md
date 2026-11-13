# Be PRO — Personal Life OS

Implementation of `BePRO_Project_Plan.pdf`, adapted to run on a **Redmi Y2 (Android 9, MIUI, 3–4 GB RAM)**.

```
backend/   Spring Boot 4 · Java 21 · Postgres · Vertex AI (Gemini)
mobile/    React Native 0.87 UI + native Kotlin collectors
deploy/    Cloud Run / Cloud SQL / Cloud Scheduler guide
```

## How it works

```
Phone (Kotlin, runs without the UI)                 Backend (Spring Boot)
┌──────────────────────────────────────┐            ┌──────────────────────────────────┐
│ WorkManager every ~15 min            │            │ POST /v1/events/batch (upsert)   │
│  ├ UsageStats: app sessions,         │  HTTPS     │  └ recompute daily_aggregates    │
│  │  screen on/off, unlocks           │ ─────────► │     (sleep inferred from screen) │
│  ├ step-counter sensor               │  JWT       │  └ anomaly alerts                │
│  ├ calendar "focus" blocks           │            │ Sunday 08:00: week summary →     │
│  ├ Health Connect (optional)         │            │  Gemini (structured JSON) →      │
│  └ activity transitions (Play svc)   │            │  1–2 insight cards               │
│ SQLite buffer → upload → notify      │ ◄───────── │ GET summary / timeline / insights│
└──────────────────────────────────────┘            └──────────────────────────────────┘
React Native screens: Today · Timeline · Trends · Insights · Settings · Setup checklist
```

### Changes from the PDF, and why

| PDF | Here | Reason |
|---|---|---|
| Kotlin + Compose app | React Native UI, **collection in native Kotlin** | You asked for React Native. Background JS would be killed by MIUI and costs RAM, so collectors/sync stay native. |
| Health Connect for sleep & steps | Phone step sensor + **sleep inferred from screen-off gaps**; Health Connect optional | Health Connect is a separate app on Android 9, and nothing writes to it without a wearable. |
| Firestore real-time sync | Removed; app fetches after each sync | One user needs no real-time push. Saves APK size and memory. |
| Pub/Sub between Scheduler and worker | Cloud Scheduler calls one authenticated endpoint | One fewer service to run and pay for. |
| JPA/Hibernate | `JdbcClient` + Flyway | Faster startup, about half the memory on Cloud Run. |
| Gemini 1.5 Pro | `gemini-3.5-flash` (configurable) | 1.5 Pro is retired; Flash is cheaper and enough for 1–2 cards a week. |
| Dedupe on `(user, source, started_at)` | `(user, source, category, sub_category, started_at)` + upsert | Several apps can start in the same second; re-collected windows update rows instead of failing. |
| `daily_aggregates.date` PK | `(user_id, date)`, days in `Asia/Kolkata` | Multi-user ready; UTC days would split at 05:30 IST. |
| Room | Plain SQLite (`EventBuffer.kt`) | One table; avoids annotation processing. |

## 1. Run the backend locally

Needs Docker and Java 21.

```bash
cp .env.example .env          # set BEPRO_DEVICE_SECRET and BEPRO_JWT_SECRET
docker compose up -d --build
curl localhost:8080/v1/health
```

Or without Docker for the app itself: `docker compose up -d db`, then
`cd backend && set -a && . ../.env && set +a && ./mvnw spring-boot:run`.

Tests (start their own Postgres in Testcontainers): `cd backend && ./mvnw test`.

`BEPRO_AI_PROVIDER=rules` produces insights offline with simple heuristics. Switch to `vertex` once
`gcloud auth application-default login` works and `GOOGLE_CLOUD_PROJECT` is set.

## 2. Build and install the app on the Redmi Y2

One-time machine setup (Android SDK is at `~/Android/Sdk`):

```bash
echo 'export ANDROID_HOME=$HOME/Android/Sdk' >> ~/.bashrc
echo 'export PATH=$PATH:$ANDROID_HOME/platform-tools' >> ~/.bashrc
```

On the phone (MIUI):
1. Settings → About phone → tap **MIUI version** 7 times.
2. Settings → Additional settings → Developer options → turn on **USB debugging**, **Install via USB**
   and **USB debugging (Security settings)**. The last two need a Mi account and a SIM in the phone.
3. Connect USB, accept the RSA prompt, check with `adb devices`.

Build a release APK (fast on the phone; debug builds feel slow on a Snapdragon 625):

```bash
cd mobile
npm install
cd android && ./gradlew assembleRelease
adb install -r app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

If install fails with an ABI error, use `app-universal-release.apk`.

For live-reload development instead: `npm start` in one terminal, `npm run android` in another.

### Connect the phone to the backend

- Laptop backend over USB: `adb reverse tcp:8080 tcp:8080`, then use `http://localhost:8080` in the app.
- Cloud Run: use its `https://…run.app` URL (see `deploy/cloud-run.md`).

The pairing secret is `BEPRO_DEVICE_SECRET`.

### The setup checklist matters on MIUI

The first screen walks through it. Skip any of these and data collection silently stops:
**Usage access** (required) · **Autostart** · **Battery saver → No restrictions** · ignore battery
optimisation · lock the app in recents. Settings shows "Last collected"; if it's hours old, MIUI is
blocking the app.

## API

All under `/v1`, JWT bearer auth except where noted.

| Method | Path | Purpose |
|---|---|---|
| POST | `/auth/token` | device id + pairing secret → 30-day JWT (no auth) |
| POST | `/events/batch` | upload ≤1000 events, idempotent |
| GET | `/events?category&from&to&page&size` | raw events |
| GET | `/summary/today` | today + yesterday aggregates, last sync time |
| GET | `/summary/week?end=` | 7-day summary (what the AI sees) |
| GET | `/summary/daily?from&to` | one row per day, ≤92 days |
| GET | `/timeline?date=` | 24 hourly buckets |
| GET | `/insights/latest` | newest weekly batch + active alerts |
| GET | `/insights?since&limit` | history |
| PATCH | `/insights/{id}` | `{"status":"dismissed"|"actioned"}` |
| POST | `/digest/run?force=` | generate the weekly digest now |
| POST | `/internal/digest/run` | Cloud Scheduler, `X-Bepro-Internal-Token` header (no JWT) |
| GET | `/export/events.csv?from&to` | CSV export |
| GET | `/health` | liveness (no auth) |

## Known limits

- **Steps before a reboot can be lost** if the phone restarts before the next collection run (the counter resets at boot).
- **Sleep is an estimate** from the longest night-time screen-off gap. Correct it on the Today screen; manual entries win.
- Notifications are polled after each sync (no Firebase), so a digest notification arrives at the next sync after it's generated.
- Release builds are signed with the debug key, which is fine for sideloading. Create your own keystore before sharing the APK.
