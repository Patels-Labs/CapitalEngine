# CapitalTrade — Plan

> **Artifact:** `CapitalTrade.jar` (optional add-on) · **Depends on:** CapitalEngine ([../core/Plan.md](../core/Plan.md)) via [../api/Plan.md](../api/Plan.md)
> **Parent plan:** [../Plan.md](../Plan.md) · **Works with or without** [CapitalMarket](../market/Plan.md)

## 0. Build status
**Built and tested** (`./gradlew :trade:test`: 16 tests, 5 pure-logic and 11 MockBukkit integration tests with the real CapitalEngine). Not yet done: the real-server smoke test on 1.21.11 and 26.x, joining once through Geyser, a check with real Citizens NPCs, and the MySQL and PostgreSQL migrations against real servers (only SQLite is exercised). `sim` covers Trade with Market and Bank (`with-trade`), which is where "Done when" item 2 is checked. Not covered by a test: an export that core refuses with `POLICY_LIMIT` (quotes already stay inside core's cap, so it can't be provoked), and a commit that is still running after 30 seconds. The jar builds as `CapitalTrade-0.0.0.jar` until Release Please adds `trade/version.txt`.

**What exists:** Ports (block and NPC), the Port menu (Export, Import when configured, Port News, Cargo), exports paid with `issue` inside the quota, the daily budget and core's cap, opt-in imports (tariff to the Treasury, the rest destroyed), Cargo, the daily world-price walk, weekly booms with Port News, crash recovery of `PENDING` shipments, the `capitaltrade` IndicatorProvider, PlaceholderAPI, `/trade help` and [docs/player-guide.md](docs/player-guide.md).

**Decisions made while building** (the sections below are updated to match):
- **Fewer classes.** `Exports`, `Imports`, `Cargo` and `TradeIndicators` are folded into `TradeService`, which runs everything on one worker thread (as Market does), so quotas need no per-item lock. The pure maths for both directions is `port/Slide`.
- **Totals are differences of one rounded cumulative function**, so selling in stacks pays exactly what selling at once does. Exports round down, imports round up, and `tariff + destroyed = total` exactly.
- **Items always come back through Cargo.** The service never touches inventories: the caller takes the items first, and anything not exported (over the quota or budget, a failed payment, an unpaid shipment found at startup) and every import goes into Cargo in the same database transaction that settles the shipment. The caller then collects Cargo into the inventory; if the player has logged off by then, the items go back into Cargo. A crash can still lose items in two brief moments, both marked `ponytail:`: between taking them from the inventory and the worker writing the shipment, and between emptying Cargo and giving the items. Because of this the simulator can call `export`/`importItems` directly; no separate test hook is needed.
- **An unclear commit is resolved with `findByReference`** before anything is handed back, so items are never returned after their coins were paid. A commit still running after 30 seconds leaves the shipment `PENDING` ("The ships are taking longer than usual") and is settled when it completes. Startup is the only time every `PENDING` row is settled; `/trade admin reload` doesn't touch them.
- **A catch-up Week event ends last week's boom** without starting a new one.
- **No `quota_used` table.** Today's quota use and coins are summed from `shipments` (which gained a `day` column), counting `PENDING` rows so a slow commit can't be sold twice, and cached in memory per day.
- **`world_price` keeps one row per item** (today's price, yesterday's for ▲/▼, and the day it last moved) instead of a full history. The walk moves at most once per day index, so repeated or catch-up Day events can't double-step it.
- **Quota players** = `max(1, activePlayersToday, yesterday's activePlayersToday)`. Yesterday's comes from the last Day event, or from `avgActivePlayers7d` after a restart.
- **The export budget** is the smallest of `exports.daily-budget`, `monetaryPolicy.budget("exports")` (CapitalMarket answers empty, so it has no effect today) and core's `issueRemainingToday`. Quotes already respect core's cap, so `POLICY_LIMIT` is a fallback that still returns the items.
- **Block Ports are a barrel with a hologram**, made the same way as Market stalls, instead of a `[Port]` sign (players can edit signs).
- **NPC Ports don't use the Citizens API.** Any entity an administrator looks at (a Citizens NPC, a named villager) becomes a Port, recognised by its UUID on right-click, so no new dependency was needed. It hasn't been tried with real Citizens yet, and an NPC moved after its Port was made needs the Port made again (the "near a Port" check uses where it stood).
- **Port News is printed in chat** (the menu button and `/trade news`) rather than shown as a menu page, so every client sees all of it.
- **`importItems` takes `maxTotal`**, the price the player agreed to, like Market's `quickBuy`. `quotaLeft` returns a future.
- Ports are removed by name (`/trade admin port remove <name>`).
- **`exports.daily-budget` defaults to 1000, not 5000.** In `sim` exports were the biggest source of new money on 20 players, and 5000 let them empty the server's stock of surplus items (prices shot past the world price). 1000 is the lowest budget that still holds the Market price of a surplus item near its world price ([sim/RESULTS.md](../sim/RESULTS.md) §2).

**Left for later:** a `trade` entry in `release-please-config.json` and `.release-please-manifest.json` before the first release (repo files, not this module's); a Port keeper that follows a moved Citizens NPC.

## 1. Purpose and scope
CapitalTrade adds an NPC **world market** at **Ports**, so the server behaves like a small country trading with the rest of the world. It's a sell shop done right:
- **Exports.** Players sell items to "faraway lands".
  - Each item has a **world price** that drifts day to day.
  - Demand is limited: each item has a **daily quota**, and the price falls as the quota fills.
  - Exported items leave the game, which is an item sink. The coins come from outside, which is new money.
- **Imports** (off by default). Players buy owner-chosen items from abroad at the world price plus a **tariff**. The coins leave the game (a money sink) and the items arrive (an item source).
- **Port News:** an occasional fun demand boom, such as *"Faraway lands want PUMPKINS this week!"*

**Why owners want it:**
- **It gives Market prices a floor and an anchor.** If wheat is cheap in the Market, players buy it there and export it, which pushes the Market price back up. That's real-world arbitrage, in a form kids understand as "sell to the ships".
- **It gives small servers another place to sell,** without the infinite money printing of a classic sell shop.

## 2. Not in scope
- **The player-to-player market.** That's CapitalMarket. Trade never reads or writes Market data.
- **Exchange rates between currencies.** That's the future CapitalNations add-on.
- **Changing vanilla drops.**

## 3. Depends on
- **Required:** `CapitalEngine` (API ≥ 1.0).
- **Soft:** `Citizens` (Port NPCs), `PlaceholderAPI`, `Floodgate`.
- **No other add-on.** It reads `MonetaryPolicy.budget("exports")` only if some provider answers it. CapitalMarket doesn't today, so config is used.

## 4. Package layout and key classes
```
com.patelheet.capitalengine.trade
├─ CapitalTradePlugin
├─ TradeService            public façade for GUI, commands, simulator (§5.6): exports, imports, Cargo,
│                          day/week handling, and the IndicatorProvider "capitaltrade" (§5.7)
├─ world/
│  ├─ WorldPrices          daily mean-reverting random walk per item              (pure Java)
│  └─ Booms                weekly random demand boom                            (pure Java)
├─ port/
│  └─ Slide                quotas, the export and import price slide, tariff split (pure Java)
├─ Ports                   port locations (barrel block or NPC entity), "at a port" check
├─ Actions, Menus, Menu    what a player does; the Port menu (Export, Import, Port News, Cargo)
├─ TradeCommand            /trade
├─ TradeForms              Floodgate forms (the only class that touches Floodgate)
├─ TradeExpansion          PlaceholderAPI
├─ Items, Messages, FileUpdater   small copies of Market's, since add-ons share no code
└─ storage/Store           own DB, migrations in trade-db/<dialect>/
```

## 5. How it works

### 5.1 What can be traded
- **`exports.yml`**: owner-listed items, each with a `base` world price and a `quota-per-active-player`.
  - The shipped defaults are about 40 everyday goods (crops, wood, ores and ingots, wool, common mob drops).
  - Base prices are deliberately below typical Market prices, so player-to-player trading stays better most of the time.
  ```yaml
  wheat:      { base: 0.8,  quota-per-active-player: 128 }
  iron_ingot: { base: 9.0,  quota-per-active-player: 32 }
  ```
- **`imports.yml`** is empty by default. Owners list items their server lacks, for example `cherry_log` on an old map, or `sponge`.
  - **This is the one opt-in exception to "the server never creates items"** (root plan §16).
  ```yaml
  sponge: { base: 40, quota-per-active-player: 1, tariff: 0.25 }
  ```
- Items use the same "plain vanilla only" rule as Market: no custom name or lore, full durability. Trade has its own small copy of that check; the two plugins don't share code.

### 5.2 World prices (`WorldPrices`)
At each **Day** event, including catch-up days so the walk stays continuous, every listed item updates:
```
ln p(t+1) = ln p(t) + θ·(ln base − ln p(t)) + σ·ε,   ε ~ N(0,1)   θ = 0.10, σ = 0.05
p clamped to [0.5×base, 2×base]
```
- The random generator is seeded from config plus the day index, so the walk is reproducible for tests and the simulator.
- **Booms** (`Booms`, at each **Week** event, not catch-up):
  - With probability `booms.chance` (0.5), one random export item gets `× booms.multiplier` (1.3) on its world price for that week.
  - It's announced as Port News: *"⚓ Port News: Faraway lands want PUMPKINS this week! Prices are up 30% at the Port."*

### 5.3 Exports
- **Today's quota per item** is `quota-per-active-player × max(1, stats().activePlayersToday, yesterday's activePlayersToday)`: yesterday's figure is used while today's is still smaller (after a restart, the 7-day average stands in for yesterday). **A quiet server gets a small quota, so it can't print money for nobody.**
- **Price slides as the quota fills.** Selling `q` units when `u` have already been exported today:
  ```
  unit price at fill level x = p × (1 − k · x/quota)        k = 0.5   (at a full quota the price is half)
  total = cum(u+q) − cum(u),   cum(x) = ⌊p·(x − k·x²/(2·quota))⌋   (the integral, rounded once, so stacks add up exactly)
  ```
  The amount that can be exported is capped at the quota remaining. The GUI shows "Ships can take 340 more wheat today".
- **Flow** (one transaction, at a Port):
  1. Remove the items from the player's inventory. On the worker, re-quote, then write a `PENDING` row and put any surplus the ships can't take into Cargo, in one database transaction.
  2. `transaction("trade.export", "Sold 64 wheat to faraway lands").issue(player, total).reference("exp:<id>")`.
  3. If it succeeds, mark it `DONE`. The items are **gone**, because they were exported.
     If it fails, mark it `FAILED` and put the items in Cargo in the same transaction, and show a plain message, for example "The ships are full for today!" for `POLICY_LIMIT`. Cargo is then collected straight into the inventory.
  4. Startup recovery uses `findByReference`, the same pattern as Market: a paid row is finished, an unpaid one hands its items back through Cargo.
- **Why `issue` and not `serverPay`:** export money really is new money entering the server from outside. It must not drain the Treasury that pays for jobs and interest. Core's `issue-per-day` cap for CapitalTrade is the owner's hard limit.
- **Daily export budget:** the smallest of `monetaryPolicy.budget("exports")` (if any provider answers), `exports.daily-budget` from config, and core's remaining `issue-per-day` for CapitalTrade. Quotes sell only what the budget covers; once it's used up, exports close until tomorrow.

### 5.4 Imports
- **Quota:** the same per-active-player rule as exports.
- **Price rises as the quota fills:**
  ```
  unit price = p × (1 + tariff) × (1 + k · x/quota)
  total = cum(u+q) − cum(u),   cum(x) = ⌈p·(1+tariff)·(x + k·x²/(2·quota))⌉
  tariffPart = ⌊total × tariff/(1+tariff)⌋,   restPart = total − tariffPart
  ```
- **Flow** (one transaction):
  - `transaction("trade.import", "Bought 1 sponge from abroad")`
  - `.move(player → Treasury, tariffPart)`: the tariff stays in the server as government income (skipped when it's 0).
  - `.destroy(player, restPart)`: the rest is paid to foreigners and leaves the economy.
  - `.reference("imp:<id>")`
  - The player agreed to a price; if the quote has risen above it, nothing happens ("The price has changed").
- **Delivery:** on success the row is marked `DONE` and the items go into **Cargo** in the same transaction, then straight into the inventory. Whatever doesn't fit stays in Cargo (collect at any Port).
- **Off by default.** If `imports.yml` is empty, the Import tab is hidden.

### 5.5 Ports
- **Creating a Port:** `/trade admin port create [name]` while looking at a block (a barrel with a floating "⚓ Port" sign is placed on top, like a Market stall; it needs three clear blocks above), or at an entity such as a Citizens NPC (recognised by its UUID; no Citizens API is used).
- **Using a Port:** the Port GUI opens when right-clicking a Port, or with `/trade` within `ports.radius` (8 blocks) of one.
  - With `ports.anywhere: true`, `/trade` works everywhere. That's off by default, so Ports are destinations, like harbours.
- **The GUI** has three tabs:
  - **Export:** items with ▲/▼ world price, "ships can take N more today", and a Sell button with 1 / 16 / 64 / All.
  - **Import:** shown only if any imports are configured.
  - **Port News:** this week's boom, and today's busiest exports (printed in chat, so every client shows all of it).
  - **Cargo:** collects anything waiting.

### 5.6 `TradeService` (public façade)
```java
CompletableFuture<Quote> quoteExport(String item, int qty);  CompletableFuture<TradeResult> export(UUID p, String item, int qty);
CompletableFuture<Quote> quoteImport(String item, int qty);  CompletableFuture<TradeResult> importItems(UUID p, String item, int qty, long maxTotal);
double worldPrice(String item);  CompletableFuture<Long> quotaLeft(String item, boolean export);
CompletableFuture<Map<String,Integer>> takeCargo(UUID p);  CompletableFuture<List<Component>> news();
void onDay(Stats ended, long newDay, boolean catchUp);  void onWeek(long week, boolean catchUp);  CompletableFuture<Void> idle();
```
Items are plain item keys, in the same format as Market's. The service never touches inventories: `export` expects the caller to have taken the items already, and anything it doesn't export goes to Cargo. So the simulator calls `export`/`importItems` directly (through `CapitalTradePlugin.service()`), with no separate hook.

### 5.7 Indicators (`IndicatorProvider` id `capitaltrade`)
Cached and refreshed after every trade (cheap counters) and at each Day event:
- exports value today, and new money from exports today
- imports value today, tariffs collected today, coins destroyed by imports today
- trade balance (exports minus imports)
- this week's boom item
- quota used, as a percentage

## 6. Data (own database, `plugins/CapitalTrade/trade.db` by default)
```
world_price  (item PK, day, price, prev)          one row per item: today's price and yesterday's
shipments    (id PK, uuid, item, direction EXPORT|IMPORT, qty, total, tariff, day, ts, status PENDING|DONE|FAILED)
booms        (week PK, item, multiplier)
ports        (id PK, name UNIQUE, world, x, y, z, kind BLOCK|NPC, npc_id NULL)
cargo        (uuid, item, qty, PK(uuid, item))
```
Today's quota use is summed from `shipments` (the day's PENDING and DONE rows), so there's no separate `quota_used` table. MySQL and PostgreSQL tables take a `prefix` (default `trade_`).

## 7. Config (abridged)
```yaml
storage: { type: sqlite }
world-prices: { theta: 0.10, sigma: 0.05, min-x: 0.5, max-x: 2.0, seed: 12345 }
exports: { slide-k: 0.5, daily-budget: 1000 }
imports: { slide-k: 0.5 }
booms: { chance: 0.5, multiplier: 1.3 }
ports: { radius: 8, anywhere: false }
```
Other files: `exports.yml`, `imports.yml`, `messages.yml`.

## 8. Commands and permissions

| Command | Permission (default) | What |
|---|---|---|
| `/trade` | `capitaltrade.use` (all) | Port GUI (near a Port, unless `anywhere`) |
| `/trade sell [amount\|all]`, `/trade buy <item> <amount>`, `/trade yes` | `capitaltrade.use` | Export the held item, import, confirm (typed fallbacks for the menu) |
| `/trade news`, `/trade help [topic]` | `capitaltrade.use` | Port News; in-game help |
| `/trade cargo` | `capitaltrade.use` | Collect imported items held for you |
| `/trade admin port create\|remove\|list` | `capitaltrade.admin` (op) | Manage Ports |
| `/trade admin boom <item> [x]` | op | Start a boom now (for events) |
| `/trade admin reload` | op | Reload |

PlaceholderAPI: `%capitaltrade_price_<item>%`, `%capitaltrade_quota_<item>%`, `%capitaltrade_boom%`.

## 9. Threading and failure handling
- **Inventory changes and GUIs:** main/player thread.
- **Money:** async API calls. The result switches back to the player's thread to finish or roll back.
- **Quotas** update in memory on the one worker thread (no locks needed), and persist with the shipment.
- **On `POLICY_LIMIT` or a used-up budget:** "The ships are full for today! Try the Market."
- **On `ECONOMY_PAUSED`:** "The Port is closed for a moment."
- **The items always go back,** through Cargo, written in the same database transaction as the shipment's status.

## 10. Tests
- **Unit tests** (pure Java):
  - `WorldPrices`: mean reversion, clamps, determinism from the seed
  - quota slide integral: the sum of per-stack prices equals the closed form, and nothing is paid beyond the quota
  - import pricing and the tariff split
  - `Booms` probability with a seeded generator
- **Integration** (MockBukkit plus core):
  - export issues exactly the quoted coins, and the items are gone
  - import: Treasury receives the tariff, the rest is destroyed, money supply falls by that amount
  - `POLICY_LIMIT` returns the items
  - crash after `PENDING` recovers by reference
  - the Import tab is hidden when `imports.yml` is empty
  - quotas grow with active players, and a full quota prints nothing
  - exports stay inside core's `issue-per-day` cap and hand back what it can't cover
  - Port News announces a boom, catch-up weeks never start one, and the price walk moves once per day

## 11. Done when
- [ ] On a 2-player server, exports pay a fair-but-low price, quotas stop money printing, and Port News booms are visible.
- [x] With CapitalMarket installed, cheap Market items get bought and exported (seen in `sim`), which lifts the Market price toward the world price.
- [ ] Imports are off by default. When enabled, they're a measurable money sink and the tariffs reach the Treasury.

## 12. Open questions
1. **Should CapitalMarket's autopilot answer `budget("exports")`,** so exports shrink when prices run hot? *Later.* It's one line in Market once it's wanted, and Trade already reads it.
2. **Should world prices follow Market prices** instead of fixed bases? *No.* Independent world prices are what make arbitrage meaningful.
