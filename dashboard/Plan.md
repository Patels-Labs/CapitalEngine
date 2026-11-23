# CapitalDashboard — Plan

> **Artifact:** `CapitalDashboard.jar` (optional add-on, **owners and staff only**) · **Depends on:** CapitalEngine ([../core/Plan.md](../core/Plan.md)) via [../api/Plan.md](../api/Plan.md)
> **Parent plan:** [../Plan.md](../Plan.md)

## 0. Build status (what exists, and where the code differs from this plan)
**Built and tested (`./gradlew :dashboard:test`):** snapshots of `stats()` and every `IndicatorProvider` (§5.1), downsampling, end-of-day totals, the alert inbox with deduplication and all five built-in rules (§5.2, §5.3), staff chat / console / Discord delivery, `/cdash` with `alerts`, `ack`, `plugins`, `web` and `reload` (§5.4), the web page with one-time login, session cookie, CSRF-protected Ack and the read-only JSON API (§5.5), and Prometheus `/metrics`.

**Tests:** `PureLogicTest` (snapshot maths, `Downsampler`, every rule, Prometheus and JSON output), `StoreTest` (every statement on SQLite; on PostgreSQL/MySQL when `CAPITAL_TEST_POSTGRES` / `CAPITAL_TEST_MYSQL` point at a `capitalengine_test` database), `WebTest` (every data route and `/metrics` refuse a missing or bad cookie or token with 401 and no data; codes are single-use and expire; 10 failures per IP per hour; Ack needs the CSRF token; only token hashes are stored), `DashboardFlowTest` (MockBukkit with the real CapitalEngine and fake add-ons: a snapshot holds core and provider numbers and skips a slow or throwing provider, a plugin issuing 5,000 coins raises `dash.plugin_printer` in the first snapshot and staff get one line, every event type becomes an alert, day totals skip catch-up days, `/cdash` output, web off and on 127.0.0.1 by default, `/api/summary` with a session).

**Deliberate differences from the sections below:**
- **Layout (§4):** fewer classes. `Snapshotter` is `CapitalDashboardPlugin.takeSnapshot` plus the pure `snapshot/Snapshot` (flattening) and `snapshot/Downsampler` (bucketing; `Store.downsample` runs it). `Notifier` and `CdashCommand` sit in the root package so `alerts/` (`AlertInbox`, `BuiltInRules`) stays free of `org.bukkit`. `web/Json` is a 60-line JSON writer. No `command/` package.
- **Schema (§6):** `key` is reserved in MySQL and `ENUM` isn't portable, so the column is `metric` and the resolution column is `res` (`m5`, `h1`, `d1` as text); `count` is `hits`. Migrations are `dashboard-db/<dialect>/V<n>__*.sql` for SQLite, MySQL/MariaDB and PostgreSQL, with the same optional table `prefix` as Market (default `dash_` outside SQLite).
- **Units:** coin amounts are stored in the smallest unit, as `Stats` gives them. `Unit.COINS` indicators are read the same way (Market publishes them in the smallest unit); the API doesn't say which, see "API gaps". Only the page, `/cdash` and `/metrics` divide by the currency's decimals. `/metrics` reports whole coins.
- **Net new money** per plugin is `issued − destroyed`; Treasury flows are recycled money and don't count. `dash.plugin_printer` fires when a plugin's net is above `printer-share` of today's `issuedToday` and above `printer-min-coins`.
- **Rule sources:** `dash.plugin_printer` and `dash.unknown_plugin` use the plugin they are about as the alert source, so each plugin dedupes separately; the other rules use `CapitalDashboard`. `dash.unknown_plugin` remembers plugins through the `metrics` table, so it fires once per plugin across restarts (on a fresh install it fires once, at INFO, for every plugin that already moves money).
- **Deduplication** counts a repeat whether or not the first alert was acknowledged, and a repeat is not delivered again. A condition that keeps firing every snapshot stays one alert until it has been quiet for `dedupe-minutes`.
- **Alert sources:** a failed `CapitalAuditEvent` becomes `CapitalEngine` / `ledger.audit`, the code core's own alert uses, so they merge. `CapitalPolicyLimitEvent` is `<plugin>` / `core.policy_limit`, deduplicated by the normal window (60 minutes). `CapitalTransactionEvent` is not used: `stats()` already carries every flow.
- **Day totals** are stored under `endedDay().dayIndex()` and only when `catchUp` is false (core doesn't reset its counters for days missed while offline, so those `Stats` aren't that day's). Downsampling and expired-session cleanup run on every Day event, including catch-up ones.
- **Snapshot timer:** checked every minute and taken once `snapshot-minutes` have passed, so `/cdash reload` changes it without rescheduling. The first snapshot is taken one tick after start. Points are stored as `m5` whatever the interval.
- **Slow providers:** each `indicators()` call runs on a small pool and is abandoned after 100 ms. A provider whose `id()` is empty, `core` or already used is skipped too.
- **`/cdash` summary:** the "Prices" line is generic: one line per `IndicatorProvider` with its first three indicators (the dashboard can't know which number is a price index). Hover text is extra; every figure is in the line itself.
- **Web API additions:** `GET /api/series?...&daily=1` reads the end-of-day totals (the "per day" charts); `POST /api/ack` with body `id=<id|all>` and header `X-CSRF` is the Ack button. The CSRF token comes in `/api/summary` and is derived from the session token. `/login` redirects to `/` with a 302. Responses carry `Content-Security-Policy: default-src 'self'`, `X-Frame-Options: DENY` and `Cache-Control: no-store`. The web page's own text (headings and labels in `index.html` and `app.js`, the login error pages in `WebServer`) and console log lines are English in the jar; chat, alert, Discord and metric-label text is in `messages.yml`.
- **Web server:** started when `web.enabled` or `metrics.enabled` is true. With only metrics on, everything except `/metrics` returns 404. `/cdash web` says the page is off when `web.enabled` is false. The rate limit uses the connection's address, never a forwarded header, so behind a reverse proxy every visitor shares one limit.
- **Metrics token:** generated (and `config.yml` saved) only the first time the server starts with `metrics.enabled: true` and an empty token, so an untouched config stays untouched.
- **No chest menu:** `/cdash` is chat only, as in §5.4, and the command has no command-level permission so each sub-command checks its own (a moderator with only `capitaldash.alerts` can use `/cdash alerts`). Each alert line has a clickable `[Ack]` and the typed command next to it for Bedrock.
- **Discord:** `allowed_mentions` is empty, so text from other plugins can never ping anyone. After a failure it waits 10 minutes and logs each failure.

**API gaps (not added, worked around):**
- `Indicator`'s Javadoc doesn't say whether `Unit.COINS` values are coins or the smallest unit. The dashboard assumes the smallest unit, as Market does. Worth one Javadoc line in `api`.
- `CapitalDayEvent.endedDay()` on a catch-up event is not the ended day's totals (core keeps today's counters), and its Javadoc doesn't say so. The dashboard skips catch-up days.

**Not built yet / needs a real server:** the page has not been opened in a browser yet (`app.js` is only syntax-checked; its endpoints are tested), so the manual check at desktop and phone width with 1 and 90 days of data (§10) is still open, and the §11 items marked below. The `dash.plugin_printer` test uses a mock plugin calling `api.issue` directly: legacy-Vault attribution through `JavaPlugin.getProvidingPlugin` can't resolve test classes, and core's `VaultTest` already covers that the flow is booked under the calling plugin.

**§11 coverage:** automated: "a Jobs-style plugin triggers `dash.plugin_printer` within one snapshot", "local-only by default, and an unauthenticated request can't read any data", and, in MockBukkit, "an add-on's section appears from its `IndicatorProvider` with no code referencing it" (a fake provider). Needs a real server: installed alone with core on Paper (in game and on the web), and with the real Market, Bank and Trade jars.

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
├─ CapitalDashboardPlugin  wiring, event listeners, the snapshot timer and takeSnapshot (stats() + all indicators → store, then rules)
├─ CdashCommand            /cdash
├─ Notifier                staff chat, console, Discord webhook (rate-limited)
├─ Messages, FileUpdater   as in Market
├─ snapshot/               (no org.bukkit)
│  ├─ Snapshot             stats() + indicators → named metrics
│  └─ Downsampler          5-min points older than 7 days → hourly; older than 90 days → daily
├─ alerts/                 (no org.bukkit)
│  ├─ AlertInbox           dedupes and stores alerts from every source, hands new ones to Notifier
│  └─ BuiltInRules         a few owner-useful rules computed from snapshots (§5.3)
├─ web/                    (no org.bukkit)
│  ├─ WebServer            HttpServer, routes, auth filter
│  ├─ Auth                 one-time login codes → session cookie
│  ├─ JsonApi              read-only JSON endpoints (also used by /cdash)
│  ├─ Json                 tiny JSON writer
│  └─ Metrics              Prometheus text format
└─ storage/Store           own DB, migrations (resources/dashboard-db/<dialect>/)
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
Market: Price index 1.02 · Prices this week 0.6% · New money per day 2,800 coins
Alerts: 2 open — /cdash alerts
```
(One line per `IndicatorProvider`, with its first three indicators.)
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
  - `GET /api/series?metric=core.issuedToday&range=30d&daily=1` (end-of-day totals)
  - `POST /api/ack` (`id=<id|all>`, header `X-CSRF`), the only write
- **Prometheus** (`metrics.enabled: false` by default): `GET /metrics` with `Authorization: Bearer <metrics-token>` returns the latest snapshot values as gauges, for example `capital_core_money_supply`, `capital_flow_net{plugin="Jobs"}`, `capital_indicator{provider="capitalmarket",key="priceIndex"}`.

## 6. Data (own database, `plugins/CapitalDashboard/dashboard.db` by default)
```
metrics    (metric PK, label, unit, provider)
snapshots  (ts, metric, value DOUBLE, res TEXT m5|h1|d1, PK(metric, res, ts))
daily      (day_index, metric, value, PK(day_index, metric))
alerts     (id PK, first_ts, last_ts, source, code, severity, message, hits, acked_by NULL, acked_ts NULL)
sessions   (token_hash PK, player_uuid, created, expires, last_ip)     player_uuid is "console" for console logins
```
SQLite by default; MySQL/MariaDB and PostgreSQL with the same `storage:` keys as Market (see §0).

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
metrics: { enabled: false, token: "" }   # generated the first time the server starts with metrics enabled
```
`printer-min-coins` is in whole coins (parsed with the currency). `/cdash reload` re-reads everything except `storage`.

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
