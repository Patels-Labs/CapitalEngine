# CapitalEngine (core) — Plan

> **Artifact:** `CapitalEngine.jar` (Paper plugin, **required**) · **Implements:** [../api/Plan.md](../api/Plan.md)
> **Parent plan:** [../Plan.md](../Plan.md)

## 1. Purpose and scope
The core is the economy's **source of truth**. It does six things:
1. **The ledger:** double-entry bookkeeping, the Mint, the Treasury, and every account and balance.
2. **`/money` and `/pay`:** the only player features core has.
3. **Vault and VaultUnlocked adapters,** so every existing Vault plugin works.
4. **Per-plugin attribution and owner-set limits** for money creation and Treasury spending.
5. **Owner tools:** balance import, audit, reverse, give/take, the nightly self-check.
6. **Shared plumbing that add-ons need:** the API implementation, the economic clock (Day and Week), activity tracking (active players, dormant players) and starter coins.

**Core on its own is a complete economy plugin.**

## 2. Not in scope
- **Market, jobs, bank, prices, rates, autopilot** (add-ons).
- **Dashboard, web UI** (CapitalDashboard). Core logs and fires events only.
- **Shared-account membership and multiple currencies** (later, see api §12).
- **Several servers sharing one ledger database** (BungeeCord/Velocity networks). In Phase 0, each database belongs to exactly one server (§9.4).

## 3. Depends on
- **Paper 1.21.11 → 26.2.** Compiled against `paper-api` 1.21.11 with `--release 21`, so one jar runs on Java 21 (1.21.11) and Java 25 (26.x) servers. No NMS. See [../CLAUDE.md](../CLAUDE.md).
- **Required:** none.
- **Soft:**
  - `Vault` / `VaultUnlocked`: provider registration.
  - `PlaceholderAPI`: placeholders.
  - `floodgate`: Bedrock yes/no forms for `/pay` confirmation (`compileOnly org.geysermc.floodgate:api`).
- **Libraries** are loaded through `plugin.yml` `libraries:` (Paper's library loader, no shading):
  - `com.zaxxer:HikariCP`
  - `org.postgresql:postgresql` (optional)
  - SQLite and MySQL drivers ship with Paper.
- **Compile only:**
  - `capitalengine-api` (project)
  - `net.milkbowl.vault:VaultUnlockedAPI:2.15` (CodeMC repo, the latest published there). It contains **both** the legacy `net.milkbowl.vault` and the `vault2` interfaces, so the separate `com.github.MilkBowl:VaultAPI` dependency is not needed.

## 4. Package layout and key classes
```
com.patelheet.capitalengine.core
├─ CapitalEnginePlugin        bootstrap & shutdown order (§5.1)
├─ ledger/
│  ├─ Ledger                  in-memory balances, global lock, validate+apply, reverse, txId sequence   (no Bukkit imports)
│  ├─ LedgerRules             the rules from api §5.3/§5.4 as pure functions                   (no Bukkit imports)
│  ├─ Acct, Leg, Req, Applied, Activity, Item   internal value types (Item = NewAccount | PendingTx | Barrier)
│  └─ PersistQueue            single writer thread, group commit, retry, pause, spill file
├─ storage/
│  ├─ Database                HikariCP pool, dialect (sqlite | mysql/mariadb | postgres)
│  ├─ Migrations              runs resources/db/{dialect}/V{n}__*.sql in order, records schema_version
│  ├─ JournalRepo             accounts, balances, journal, postings, per-day policy counters, audit queries, group-commit write
│  ├─ PlayerRepo              players + activity_daily
│  └─ ClockRepo               clock indexes + the single-server lock row
├─ policy/
│  ├─ PolicyManager           per-plugin limits + today's counters (persisted)   (no Bukkit imports)
│  └─ Attribution             finds the calling plugin for legacy Vault calls (StackWalker)
├─ vault/
│  ├─ VaultHook               registers providers; guarded vault2 load
│  ├─ VaultOps                shared by both adapters: account lookup, vault-mode → ledger legs
│  ├─ LegacyVaultEconomy      implements net.milkbowl.vault.economy.Economy
│  └─ Vault2Economy           implements net.milkbowl.vault2.economy.Economy (only class touching vault2 types; has its own register())
├─ CoreConfig, Messages       parsed config.yml; messages.yml (MiniMessage)
├─ api/ApiImpl, CurrencyImpl  implements CapitalApi on top of Ledger; the currency
├─ clock/
│  ├─ EconomyClock            day/week boundaries, catch-up
│  └─ ClockControl            internal hook (not API) so the simulator can advance time (needs -Dcapitalengine.sim=true)
├─ activity/ActivityTracker   join/quit, non-AFK minutes per day, dormant set, active counts
├─ audit/
│  ├─ Auditor                 nightly self-check, CapitalAuditEvent
│  └─ AuditView               /ce audit  (/ce reverse is Ledger.reverse)
├─ importer/Importer          /ce import (idempotent through its references, so no table of its own)
├─ command/                   MoneyCommand, PayCommand, CeCommand
├─ gui/MoneyMenu              balance + last 10 transactions
└─ papi/CapitalExpansion      PlaceholderAPI (soft)
```
`Ledger` and `LedgerRules` are pure Java, so they can be unit tested directly and driven by the simulator.

## 5. How it works

### 5.1 Startup and shutdown order
**Startup:**
1. Load config and messages.
2. Open the database and run migrations. **If this fails, disable the plugin loudly and register no Vault provider.**
3. Replay `spill.jsonl` into the database if it exists (§9.2), then load all accounts and balances into memory. (Replay first so the balances loaded already include the spilled transactions.)
4. Load today's policy counters and the last clock indexes.
5. Register `CapitalApi` with `ServicesManager`, then the Vault providers (§5.6).
6. Start the `PersistQueue` writer, `ActivityTracker` and `EconomyClock`, firing any missed Day and Week events with `catchUp=true`. This happens on the first server tick, so add-ons are enabled by then.
7. Register commands and the PlaceholderAPI expansion.

**Shutdown:**
1. Stop accepting mutations (they return `ECONOMY_PAUSED`).
2. Drain the `PersistQueue` with a 10-second timeout. Anything left is written to `spill.jsonl`.
3. Close the pool.

### 5.2 The ledger
**Validate and apply**, under one global `ReentrantLock` held for microseconds:
1. Resolve accounts and check ownership:
   - Only the owning plugin can take money out of a `PLUGIN` account.
   - The Treasury can only be a source through `serverPay`.
2. Check policy: issue and Treasury amounts against the caller's remaining limits today, and the `deny` setting.
3. Check that the *net* effect leaves every non-Mint account at zero or above.
4. If a `reference` is present, check it hasn't already been used by this plugin (an in-memory index of references, filled from the database at startup).
5. Apply the balance changes in memory, bump the policy counters, and assign `txId` from an `AtomicLong` seeded from `MAX(tx_id)`.
6. Add the transaction to the `PersistQueue`. Return a future that completes when it's written.

**Persist** (single writer thread):
- Take the first queued item, then everything else already waiting (up to 500 items), and write them in **one database transaction**: account rows, journal rows, posting rows, balance upserts and policy counter upserts. It never waits to fill a batch, so under load batches grow by themselves and a quiet server has no added delay.
- After the commit, complete each future and fire `CapitalTransactionEvent` asynchronously.
- `ponytail:` one global lock plus one writer. That's plenty for a single server, where each apply takes microseconds. If a big network ever needs more, shard the lock by account.

**Why apply in memory first:** legacy Vault calls are synchronous (`withdrawPlayer` must answer right away). Applying under the lock gives an instant, correct answer, and the database catches up within about 50 ms. A crash in that window is covered by §9.2.

### 5.3 Mint and Treasury
- Both are created by the first migration with fixed UUIDs.
- **Mint:** the only account allowed below zero. `moneySupply = −mint.balance`.
- **Treasury:** a normal account that can't go below zero. Any transaction can pay into it. It can only be spent from through `serverPay`, which also applies the caller's Treasury limit.

### 5.4 Per-plugin policy
`config.yml → plugins:`. Every key is optional, and any plugin not listed uses `defaults`.

```yaml
defaults:                      # applies to any plugin not listed (incl. unknown Vault plugins)
  vault-mode: issue            # issue | treasury | deny   (what a Vault deposit/withdraw with no counterparty means)
  issue-per-day: unlimited
  treasury-per-day: unlimited
plugins:
  CapitalEngine:  { issue-per-day: 50000 }                     # starter coins
  CapitalMarket:  { issue-per-day: 20000, treasury-per-day: 50000 }
  CapitalBank:    { issue-per-day: 30000, treasury-per-day: 20000 }
  Jobs:           { vault-mode: treasury, issue-per-day: 5000 }
  SomeOldShop:    { vault-mode: deny }
```

- **Unknown plugins default to `issue`, uncapped, but logged.** "Logged" means every transaction is in the journal with the plugin's name, so there is no `log` switch. This is "observe first": installing core never breaks an existing server. The owner then looks at `/ce audit top` (or the Dashboard) and tightens limits.
- **Owner tools** (`/ce give`, `/ce take`, `/ce import`, `/ce reverse`) bypass limits and are recorded under the source name `CapitalEngine-admin`, so they never use up the `CapitalEngine` (starter coins) budget. Search them with `/ce audit plugin CapitalEngine-admin`.
- **Counters** (`issued`, `treasuryOut` per plugin) reset at the Day boundary and are persisted, so a restart doesn't reset them.
- **Hitting a limit** fails the call with `POLICY_LIMIT`, fires `CapitalPolicyLimitEvent` and logs at most once per plugin per hour.
- `/ce policy set` edits the file and reloads it live.

### 5.5 Vault calls with no counterparty
A legacy `depositPlayer(p, 50)` doesn't say where money comes from. Core uses the caller's `vault-mode`:

| Mode | `depositPlayer` | `withdrawPlayer` |
|---|---|---|
| `issue` | Mint → player (`issue`) | player → Mint (`destroy`) |
| `treasury` | `serverPay`: Treasury first, then new money, within limits | player → Treasury (`collect`) |
| `deny` | Fails ("CapitalEngine: plugin X is not allowed to create money") | Fails |

Withdrawals mirror deposits, so a player-to-player shop plugin (withdraw from the buyer, deposit to the seller) nets to about zero in either mode. The Dashboard shows **net** flow per plugin, so shop plugins don't look like money printers.

**Attribution:**
- **vault2 calls** carry the plugin name themselves.
- **Legacy calls:**
  - `Attribution` walks the stack, skipping CapitalEngine and Vault frames.
  - It takes the first class loaded by a plugin's class loader, then `JavaPlugin.getProvidingPlugin(cls)`.
  - The result is cached per calling class. If the plugin can't be identified, it's recorded as `"unknown"` under the `defaults` policy.

### 5.6 Vault adapters
`VaultHook`:
```java
if (pm.getPlugin("Vault") != null)                        // VaultUnlocked also answers to "Vault"; verify in Phase 0
    sm.register(net.milkbowl.vault.economy.Economy.class, new LegacyVaultEconomy(...), this, ServicePriority.Highest);
if (classExists("net.milkbowl.vault2.economy.Economy"))
    Vault2Registrar.register(this, ...);                  // separate class; only it references vault2 types
```

**`LegacyVaultEconomy`:**
- **Basics:** `getName()` is "CapitalEngine". `fractionalDigits()` is the configured decimals. `format` and `currencyNamePlural/Singular` come from config.
- **Accounts:** `hasAccount`/`createPlayerAccount` resolve the player.
  - A **real player** (online, cached or known) gets a `PLAYER` account.
  - **Any other UUID or name** (e.g. Towny's `town-*` fake players) gets an `EXTERNAL` account owned by the calling plugin.
- **Amounts:** `getBalance` and `has` convert `long` to `double` exactly. `withdrawPlayer`/`depositPlayer` round half-even to the smallest unit, then follow §5.5.
- **Name and world overloads:** the deprecated `String` name overloads look up the `players` table and fall back to `EXTERNAL`. World overloads ignore the world.
- **Bank methods** (`createBank`, `bankBalance`, …) return `NOT_IMPLEMENTED` and `hasBankSupport()` is `false`. CapitalBusiness will implement them later through the API.

**`Vault2Economy`:**
- **Mapping:** `UUID` accounts map exactly as above. `BigDecimal` amounts convert to `long`, rejecting any value with more precision than the configured decimals.
- **Unsupported for now:** `hasSharedAccountSupport()` and `hasMultiCurrencySupport()` return `false`.
- **Transfers:** the published API (2.15) has no account-to-account `transfer`, so Vault2 plugins get the interface default (withdraw plus deposit). Map it to a true `transfer` once the 2.20 API is on a Maven repo.
- **Verified in Phase 0** against the 2.15 jar: every `vault2.economy.Economy` method takes the calling `pluginName` first; `AccountPermission` is a plain enum (DEPOSIT, WITHDRAW, BALANCE, TRANSFER_OWNERSHIP, INVITE_MEMBER, REMOVE_MEMBER, CHANGE_MEMBER_PERMISSION, OWNER, DELETE).

### 5.7 Economic clock
- **Config:** `clock.day-starts-at: "04:00"`, `clock.timezone: server` (or an IANA zone), `clock.week-starts-on: MONDAY`.
- **Index:** `dayIndex` counts days since a fixed epoch, measured in the configured time zone from the day-start time.
- **Checking:** every 60 seconds on the global scheduler, it compares the current `dayIndex` with the last one fired.
- **Firing:** core captures `endedDay = stats()` first, then fires `CapitalDayEvent(dayIndex, date, catchUp, endedDay)` synchronously. On a week boundary, `CapitalWeekEvent` fires next. Then the new index is persisted.
- **Catch-up after downtime:** each missed day fires in order with `catchUp=true`, up to `clock.max-catch-up-days` (default 7). Older missed days are skipped with a log line.
- **Core's own Day handlers** run **before** add-ons' (core listens at `LOWEST`):
  - reset policy counters
  - roll activity over to the new day
  - run the `Auditor`
- **`ClockControl`** (`core.clock`, not in the API; refuses to work unless `-Dcapitalengine.sim=true`) offers:
  - `advanceDays(n)`: fires boundaries immediately (simulator only, guarded by a system property)
  - `recordActivity(uuid, minutes)`

### 5.8 Activity tracking
- **On join/quit:** upserts the `players` row (name, `first_seen`, `last_seen`).
- **Every minute:** each online player who has been **active** in the last 5 minutes gets +1 minute in `activity_daily`.
  - **Active** means they moved their head or body, chatted, or interacted, tracked as a cheap last-action timestamp.
  - AFK-farm time doesn't count.
- **Derived numbers:**
  - `activePlayersToday`: players with 5 or more active minutes today.
  - `avgActivePlayers7d`
  - The **dormant set:** `last_seen` older than `dormant-after-days` (default 60).
  - `activeMoneySupply = moneySupply − Σ balances of dormant players' PLAYER accounts`. **No money is moved,** and returning players simply count again.

### 5.9 Starter coins
On a player's first ever join, if `starter-coins > 0` and they haven't had them yet, core commits `transaction(CapitalEngine, "core.starter", "Welcome! Here are some coins to get you started").serverPay(player, starter-coins).reference("starter:<uuid>")`. The reference makes it idempotent.

### 5.10 Player commands
- **`/money`** (aliases `/bal`, `/balance`):
  - With no arguments as a player, opens `MoneyMenu`: a big coin icon with the balance, the last 10 transactions in plain words ("+120 coins · Sold iron at the market"), and a "Pay a friend" button.
  - **`/money <player>`** shows another player's balance (permission `capitalengine.money.others`).
- **`/pay <player> <amount>`:**
  - Pays offline players too, if they're known.
  - Paying yourself or below `pay.min` is refused.
  - **At or above `pay.confirm-above`** (default 1,000), the player is asked to confirm first, and the prompt expires after 30 seconds:
    - **Java:** a clickable chat prompt, `Pay Mia 1,500 coins? [✔ Yes] [✖ No]`.
    - **Bedrock:** chat clicks don't work through Geyser, so the same text also says "or type /pay confirm". When Floodgate is installed, Bedrock players get a yes/no form instead. The form code lives in `command/FloodgateForms`, the only class that touches Floodgate types.
  - **Bedrock names** (Floodgate prefix, e.g. `.Steve`) resolve like any other player name.
  - The recipient sees "Sam paid you 1,500 coins".

### 5.11 Owner tools (`/ce …`, permission `capitalengine.admin.*`)

| Command | What |
|---|---|
| `/ce audit player <name> [page]` | Transactions touching the player, newest first: type, memo, plugin, amount |
| `/ce audit plugin <name> [days]` | That plugin's flows (issued, destroyed, Treasury in/out, net) and recent transactions |
| `/ce audit tx <id>` | Full postings of one transaction, and whether it has been reversed |
| `/ce audit top [days]` | Plugins ranked by **net money created** |
| `/ce reverse <tx> [reason]` | Creates a reversing transaction (negated postings, type `core.reverse`). It's refused if any account would go negative (and says which), or if the transaction was already reversed. |
| `/ce give <player> <amount> [reason]` / `/ce take …` | Admin `issue` and `destroy`, attributed to `CapitalEngine` with the admin as actor. They bypass caps but are logged. |
| `/ce import <VaultProvider> [--dry-run]` | See §5.12 |
| `/ce policy [plugin]` / `/ce policy set <plugin> <key> <value>` | View or edit limits live |
| `/ce check` | Runs the self-check now (permission `.audit`) |
| `/ce reload` | Reloads config, messages and policy. Currency names and decimals change only after a restart. |
| `/ce prune` | Deletes `activity_daily` and `policy_daily` rows older than 400 days (permission `.reload`) |

### 5.12 Import
1. The owner keeps the old economy plugin installed and installs CapitalEngine. Core registers at `Highest`, so other plugins already use CapitalEngine.
2. `/ce import Essentials --dry-run` finds the old plugin's own registered Vault `Economy` provider. It reads `getBalance` for every `Bukkit.getOfflinePlayers()` and reports the count, the total, and any skipped negative or NaN balances.
3. `/ce import Essentials` posts one `issue` per player: type `core.import`, memo "Opening balance from Essentials", reference `import:Essentials:<uuid>`.
   - It's idempotent, so running it twice is harmless.
   - It's not subject to caps.
4. Core tells the owner to remove or disable the old economy, and explains how to point `/bal` at CapitalEngine if both register it (via `commands.yml`).

### 5.13 Nightly self-check (`Auditor`)
At each Day boundary, async against the database:
- `SUM(postings.amount) = 0` overall
- for every account, `SUM(postings)` equals `balances.amount`
- the database balances equal the in-memory balances
- no non-Mint balance is below zero

The result fires `CapitalAuditEvent` and is logged. **A failure is a `CRITICAL` alert.** The economy keeps running and the owner investigates with `/ce audit`.

## 6. Data
SQL is written per dialect. Amounts are `BIGINT` and IDs are `CHAR(36)` / `UUID`.

```
schema_version (version INT)
accounts       (id PK, kind, plugin NULL, acct_key NULL, owner_player NULL, name, currency DEFAULT 'default', created_at)
               UNIQUE(plugin, acct_key)
balances       (account_id PK → accounts, amount BIGINT, last_tx BIGINT)
journal        (tx_id BIGINT PK, ts, type, memo, source_plugin, actor NULL, reference NULL, reverses_tx NULL)
               UNIQUE(source_plugin, reference)  INDEX(ts)  INDEX(source_plugin, ts)
postings       (tx_id → journal, account_id → accounts, amount BIGINT, PK(tx_id, account_id))  INDEX(account_id, tx_id)
players        (uuid PK, name, first_seen, last_seen, starter_paid BOOL)  INDEX(name)
activity_daily (day_index, uuid, minutes, PK(day_index, uuid))
policy_daily   (day_index, plugin, issued, destroyed, treasury_in, treasury_out, transferred, PK(day_index, plugin))
clock          (k PK, v)                          -- last_day_index, last_week_index
server_lock    (id PK, holder, heartbeat)         -- §9.4
```
Retention: the journal and postings are kept forever (they are the books). `activity_daily` and `policy_daily` older than 400 days can be pruned with `/ce prune`.

## 7. Config (`config.yml`, abridged)
```yaml
storage: { type: sqlite, file: ledger.db }          # or mysql/mariadb/postgres + host/port/db/user/pass/pool-size
currency: { singular: coin, plural: coins, symbol: "⛁", decimals: 2, format: "<amount> <name>" }
starter-coins: 250
pay: { min: 1, confirm-above: 1000 }
clock: { day-starts-at: "04:00", timezone: server, week-starts-on: MONDAY, max-catch-up-days: 7 }
activity: { afk-after-minutes: 5, dormant-after-days: 60 }
defaults: { vault-mode: issue, issue-per-day: unlimited, treasury-per-day: unlimited, log: true }
plugins: { }                                          # see §5.4
```
`messages.yml` uses MiniMessage and plain, kid-friendly wording throughout.

## 8. Commands and permissions

| Permission | Default | Grants |
|---|---|---|
| `capitalengine.money` | everyone | `/money` |
| `capitalengine.money.others` | op | `/money <player>` |
| `capitalengine.pay` | everyone | `/pay` |
| `capitalengine.admin.audit` / `.reverse` / `.give` / `.import` / `.policy` / `.reload` | op | `/ce …` |

PlaceholderAPI: `%capital_balance%` (raw), `%capital_balance_formatted%`, `%capital_balance_short%` (1.2k), `%capital_currency%`.

## 9. Threading and failure handling

### 9.1 Threads
- **Main/global thread:** commands, GUIs, events, clock.
- **Any thread:** API calls (validate and apply under the lock).
- **The writer thread:** all database writes.
- **Async pool:** audit queries and history reads.
- **Folia:** the clock uses the global region scheduler and the GUIs use the entity scheduler from day one, so Folia support later is mostly testing.

### 9.2 Database trouble
- **A failed group commit is retried** with backoff: 0.5 s, 1 s, 2 s, then capped at 10 s.
- **If the queue backs up** (more than 5,000 pending or more than 30 s old), core goes into **paused mode**:
  - New mutations fail with `ECONOMY_PAUSED`, and players see "The bank is having a nap, try again in a minute!"
  - Vault calls return failure.
  - Reads keep working.
  - A `CRITICAL` alert fires.
  - It resumes automatically when the queue drains.
- **On shutdown or a crash while paused,** pending transactions are written to `spill.jsonl` (append-only, `fsync`'d), and replayed in `txId` order on the next start, before anything else.
- **Hard-crash window:** at most one group (about 50 ms) of applied but not yet written transactions can be lost. `ponytail:` the upgrade path is a write-ahead `spill.jsonl` append for every transaction if owners ever need zero loss.

### 9.3 Invalid input
- Negative, zero or overflowing amounts return `INVALID_AMOUNT`.
- An unknown account returns `NO_ACCOUNT`.
- Taking from someone else's plugin account returns `NOT_ACCOUNT_OWNER`.

### 9.4 Networks
Only one server may use a ledger database. On startup core writes a `server_lock` row with a heartbeat, and **refuses to start** if another live server holds it. Network support (shared ledger with row locks, or one ledger server) is a later phase.

## 10. Tests
- **Unit tests** (pure Java, `Ledger` and `LedgerRules`):
  - sum is zero
  - no negative balances
  - plugin-account ownership
  - Treasury only as a source via `serverPay`
  - the `serverPay` split, including all-or-nothing at the issue limit
  - references and duplicates
  - multi-leg net checks
  - overflow
- **`ApiContractTest`:** every rule in api §5.4, run against `ApiImpl` with SQLite in a temp folder.
- **Persistence:**
  - group commit round-trip
  - restart reloads balances
  - forced write failure leads to paused mode and recovery
  - spill and replay produce identical balances
- **MockBukkit:**
  - legacy-only Vault registration
  - legacy plus vault2 registration
  - attribution with two mock plugins calling `depositPlayer`
  - each `vault-mode`
  - `EXTERNAL` accounts for fake player names
- **Clock:**
  - boundaries in a non-UTC time zone
  - DST change days
  - catch-up ordering and `max-catch-up-days`
- **Import:** dry run, then a real run, then a second real run changes nothing.

## 11. Done when
Status after the Phase 0 build (automated = `./gradlew :core:test`, 57 tests):
*Real-server smoke test done on Paper 1.21.11 and 26.2 (`./gradlew :core:runServer [-Pmc=26.2]`, with VaultUnlocked 2.20.2): `plugin.yml` `libraries:` load, `api-version: '1.21.11'` is accepted, both Vault interfaces register, `/ce` console commands work, and shutdown is clean. Still open: a real player session (Java and Bedrock/Geyser), legacy Vault-only, real EssentialsX/Jobs, a hard `kill -9`.*
- [ ] On a fresh Paper server with **Vault**, and separately with **VaultUnlocked**, EssentialsX's `/bal` and a Jobs-style plugin work through CapitalEngine. *(Adapters, attribution and all vault-modes are covered by `VaultTest` under MockBukkit; the real-server run is still to do.)*
- [x] Every movement is visible in `/ce audit` with the correct plugin. *(`PlayerFlowTest`, `VaultTest` flows by plugin.)*
- [x] `/money`, `/pay` (with confirmation) and starter coins work. All player text is plain-language. *(`PlayerFlowTest`, `MessagesTest`; still to eyeball on a real client, Java and Bedrock.)*
- [x] `/ce import Essentials` moves balances exactly. A second run is a no-op. *(`PlayerFlowTest` with a fake provider; try it against real EssentialsX.)*
- [x] Killing the server mid-load and restarting gives a passing `/ce check`. *(`PersistenceTest`: spill, replay, auditor. A real `kill -9` run is still to do.)*
- [ ] The API jar is published, and the api §5.8 example add-on runs against core. *(Jar, Javadoc and POM build with the licence inside; the example runs in `EconomicClockTest`; publishing to the owner's Maven repo needs the repo URL and credentials.)*

## 12. Open questions
1. ~~Group and package ID~~ **Decided:** `com.patelheet.capitalengine.core`.
2. **Should `/bal` be taken from EssentialsX automatically?** *No.* Document `commands.yml` aliasing, and don't fight other plugins.
3. **Network (multi-server) support:** which phase, and which model (shared database with row locks, or one ledger server). It's deferred until someone asks.
