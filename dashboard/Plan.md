# CapitalDashboard — Plan

> **Artifact:** `CapitalDashboard.jar` (optional add-on, **owners and staff only**) · **Depends on:** CapitalEngine ([../core/Plan.md](../core/Plan.md)) via [../api/Plan.md](../api/Plan.md)
> **Parent plan:** [../Plan.md](../Plan.md)

## 1. Purpose and scope
Shows owners what their economy is doing and warns them when something looks wrong. It's **read-only**: it never moves money or changes settings. It covers:
1. **History snapshots** of core `stats()` and every registered `IndicatorProvider` (Market, Bank, Trade, or any third-party add-on).
2. **Alerts** collected from core and add-on events, plus a few built-in rules, delivered in game, to the console and to a Discord webhook.
3. **In-game summary:** `/cdash`.
4. **Web dashboard:** a small built-in web page with charts. It's local-only by default and protected by a token.
5. **Optional Prometheus `/metrics`** for owners who already run Grafana.

## 2. Not in scope
- **Changing anything,** such as policy, balances or prices. Owners use `/ce …` in core or each add-on's admin commands.
- **A player-facing page.** CapitalMarket's `/economy` is the player view.
- **Accounts, HTTPS or a public hosting service.** Owners who want it public put it behind their own reverse proxy.
- **Front-end frameworks, CDNs or chart libraries.** It's plain HTML, vanilla JS and SVG.

## 3. Depends on
- **Required:** `CapitalEngine` (API ≥ 1.0).
- **Soft:** nothing. It discovers other add-ons only through `IndicatorProvider` registrations and events.
- **JDK only:** `com.sun.net.httpserver.HttpServer` (web), `java.net.http.HttpClient` (Discord webhook).

## 4. Package layout and key classes
```
com.patelheet.capitalengine.dashboard
├─ CapitalDashboardPlugin
├─ snapshot/
│  ├─ Snapshotter          every 5 min: stats() + all indicators → snapshots table
│  └─ Downsampler          daily: 5-min points older than 7 days → hourly; older than 90 days → daily
├─ alerts/
│  ├─ AlertInbox           listens: CapitalAlertEvent, CapitalAuditEvent, CapitalPolicyLimitEvent; dedupes; stores
│  ├─ BuiltInRules         a few owner-useful rules computed from snapshots (§5.3)
│  └─ Notifier             staff chat, console, Discord webhook (rate-limited)
├─ web/
│  ├─ WebServer            HttpServer, routes, auth filter
│  ├─ Auth                 one-time login codes → session cookie
│  ├─ JsonApi              read-only JSON endpoints
│  └─ Metrics              Prometheus text format
├─ command/CdashCommand
└─ storage/                own DB, migrations
resources/web/  index.html  app.js  style.css   (served from the jar)
```

## 5. How it works

### 5.1 Snapshots
- **Every 5 minutes** (async), `Snapshotter` reads `api.stats()` and `indicators()` from each `IndicatorProvider` registration (both are cheap, cached reads). It writes one row per metric:
  - Core metrics are keyed `core.<field>`, for example `core.moneySupply`, `core.issuedToday`, `core.activePlayersToday`.
  - Per-plugin flows are keyed `core.flow.<plugin>.<issued|destroyed|treasuryIn|treasuryOut|net>`.
  - Add-on metrics are keyed `<providerId>.<key>`, for example `capitalmarket.priceIndex`, with the label and unit stored once in `metrics`.
- **Totals for each day:** at each Day event, it stores `event.endedDay()` (the final `Stats` of the day that just ended, which core captures before resetting its counters) into `daily`.
- **How long data is kept:**
  - full 5-minute detail for 7 days
  - hourly for 90 days
  - daily forever

### 5.2 Alerts
- **Where they come from:**
  - `CapitalAlertEvent`, from any plugin
  - `CapitalAuditEvent` (a failure becomes `CRITICAL`)
  - `CapitalPolicyLimitEvent` (`WARN`, deduplicated per plugin per hour)
  - the built-in rules (§5.3)
- **Deduplication:** the same `source + code` within `dedupe-minutes` (60) increments a counter instead of creating a new alert.
- **Where they go**, each with its own minimum severity:
  - **Staff in game** (permission `capitaldash.alerts`): one chat line, `⚠ [Economy] CapitalMarket: Sam sold 3,200 diamonds today (usual: 40) — /cdash alerts`.
  - **Console:** always.
  - **Discord webhook** (optional): an embed with severity colour. At most 1 message every 10 seconds, with a queue that drops `INFO` first.
- **Acknowledging:** `/cdash alerts ack <id|all>` or the web page's Ack button. Acknowledged alerts stay in history.

### 5.3 Built-in rules
These are evaluated after each snapshot, and every threshold can be configured.

| Code | Severity | Trigger |
|---|---|---|
| `dash.issuance_spike` | WARN | `issuedToday` > 3× the 7-day daily average (after 3 days of data) |
| `dash.plugin_printer` | WARN | One plugin's net issuance > 50% of today's total and > `min-coins` |
| `dash.unknown_plugin` | INFO | A new plugin appears in `flowsToday` (shown once per plugin) |
| `dash.treasury_empty` | INFO | Treasury balance is 0 for 24h (server payouts are all new money) |
| `dash.supply_drop` | WARN | Money supply fell > 20% in 7 days |

### 5.4 In game: `/cdash`
`/cdash` shows a compact chat summary with hover tooltips, in plain words even for owners:
```
━━ Economy today ━━
Money in the world: 1,284,500 coins (▲2.1% this week)
Created today: 8,420 · Destroyed today: 6,950 · Treasury: 41,200
Most new money from: CapitalBank 3,900 · CapitalMarket 2,800 · Jobs 1,100
Prices: ▲0.6% this week (CapitalMarket)
Alerts: 2 open — /cdash alerts
```
- `/cdash alerts` lists open alerts.
- `/cdash plugins` lists flows per plugin for today and the last 7 days.
- `/cdash web` gives a login link (§5.5).

### 5.5 Web dashboard
- **Bind address:** `web.bind: 127.0.0.1`, `web.port: 8765`, `web.enabled: false` by default.
  - Owners on hosting panels set `bind: 0.0.0.0`. The log then warns: "Dashboard is reachable from the internet. Put it behind HTTPS (reverse proxy) or keep it local."
- **Login:**
  1. `/cdash web` (permission `capitaldash.web`) creates a **one-time code**: random, 128-bit, valid for 5 minutes.
  2. It prints the link as clickable text plus the plain URL (so it can be copied on Bedrock or in the console): `http://<public-url-or-bind>:8765/login?code=…`.
  3. Visiting the link exchanges the code for a session cookie (`HttpOnly`, `SameSite=Strict`, lasting 7 days, stored hashed).
  - **Brute force:** failed logins are limited to 10 per IP per hour.
- **Pages:** a single page, `index.html` with `app.js`, containing:
  - **Overview cards:** money in the world, created and destroyed today, Treasury, active players, open alerts.
  - **Charts** (hand-rolled SVG line and bar charts, about 150 lines of JS):
    - money supply versus active money supply
    - created versus destroyed per day
    - Treasury in and out
  - **Plugins table:** issued, destroyed, Treasury in and out, and net per plugin, for today, 7 days and 30 days. Printers are highlighted.
  - **Add-on sections:** one auto-generated section per `IndicatorProvider`. Each shows its indicators as cards and charts, labelled and unit-formatted from the metadata.
  - **Alerts list** with Ack buttons. Ack is the only `POST`, and it's protected by a CSRF token.
- **Read-only JSON API** (cookie auth):
  - `GET /api/summary`
  - `GET /api/series?metric=core.moneySupply&range=30d`
  - `GET /api/metrics` (catalogue)
  - `GET /api/plugins?range=7d`
  - `GET /api/alerts?open=true`
- **Prometheus** (`metrics.enabled: false` by default): `GET /metrics` with `Authorization: Bearer <metrics-token>` returns the latest snapshot values as gauges, for example `capital_core_money_supply`, `capital_flow_net{plugin="Jobs"}`, `capital_indicator{provider="capitalmarket",key="priceIndex"}`.

## 6. Data (own database, `plugins/CapitalDashboard/dashboard.db` by default)
```
metrics    (key PK, label, unit, provider)
snapshots  (ts, key, value DOUBLE, resolution ENUM(m5,h1,d1), PK(key, resolution, ts))
daily      (day_index, key, value, PK(day_index, key))
alerts     (id PK, first_ts, last_ts, source, code, severity, message, count, acked_by NULL, acked_ts NULL)
sessions   (token_hash PK, player_uuid, created, expires, last_ip)
```

## 7. Config
```yaml
storage: { type: sqlite }
snapshot-minutes: 5
alerts:
  dedupe-minutes: 60
  staff-min-severity: WARN
  discord: { webhook: "", min-severity: WARN }
  rules: { issuance-spike-x: 3.0, printer-share: 0.5, printer-min-coins: 1000, supply-drop: 0.20 }
web: { enabled: false, bind: 127.0.0.1, port: 8765, public-url: "" }
metrics: { enabled: false, token: "<generated on first run>" }
```

## 8. Commands and permissions

| Command | Permission (default) | What |
|---|---|---|
| `/cdash` | `capitaldash.view` (op) | Chat summary |
| `/cdash alerts [ack <id\|all>]` | `capitaldash.alerts` (op) | List or ack alerts. This permission also receives live alert lines. |
| `/cdash plugins` | `capitaldash.view` | Per-plugin flows |
| `/cdash web` | `capitaldash.web` (op) | One-time web login link |
| `/cdash reload` | `capitaldash.admin` (op) | Reload config |

## 9. Threading and failure handling
- **Off the main thread:** snapshots, rules, the web server (its own small fixed thread pool of 4) and webhooks.
- **On the main thread:** only command output and staff chat lines.
- **If the web server fails to bind** (port in use), it logs an error and the plugin keeps working in game.
- **If the Discord webhook fails,** it logs once and backs off for 10 minutes.
- **If an `IndicatorProvider` throws or is slow** (more than 100 ms), it's skipped for that snapshot and logged once per hour. It never breaks the snapshot.
- **Security:**
  - Every web route except `/login` and the static assets needs a valid session.
  - The web page escapes all strings it shows from add-ons, such as indicator labels and alert messages.
  - No endpoint can change economy data.

## 10. Tests
- **Unit tests:** `Downsampler` correctness, built-in rule triggers and deduplication, Prometheus text format, `Auth` (code expiry, single use, rate limit).
- **Integration** (MockBukkit plus core plus a fake `IndicatorProvider`):
  - a snapshot contains core and provider metrics
  - alerts from each event type are stored and delivered
  - the web server serves `/api/summary` with a session and gives 401 without one
- **Manual:** open the page on desktop and phone width and check the charts render with 1 day and with 90 days of data.

## 11. Done when
- [ ] Installed alone with core, it shows money supply, flows per plugin and alerts, both in game and on the web.
- [ ] With Market, Bank and Trade installed, each add-on's section appears automatically, with no code referencing them.
- [ ] A Jobs-style plugin printing money triggers `dash.plugin_printer` within one snapshot.
- [ ] The web server is local-only by default, and an unauthenticated request can't read any data.

## 12. Open questions
1. **Should moderators see the web page without op?** *Use the permission `capitaldash.web`;* the owner decides who gets it.
2. **Should a static "economy report" be exportable** (a weekly HTML or PNG for Discord)? *Later, if owners ask.*
