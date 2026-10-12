# CapitalTrade — Plan

> **Artifact:** `CapitalTrade.jar` (optional add-on) · **Depends on:** CapitalEngine ([../core/Plan.md](../core/Plan.md)) via [../api/Plan.md](../api/Plan.md)
> **Parent plan:** [../Plan.md](../Plan.md) · **Works with or without** [CapitalMarket](../market/Plan.md)

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
├─ TradeService            public façade for GUI, commands, simulator (§5.6)
├─ world/
│  ├─ WorldPrices          daily mean-reverting random walk per item              (pure Java)
│  └─ Booms                weekly random demand boom                            (pure Java)
├─ port/
│  ├─ Exports              quote / sell with quota slide
│  ├─ Imports              quote / buy with tariff + quota slide
│  ├─ Ports                port locations (block/sign/NPC), "at a port" check
│  └─ Cargo                per-player holding for imported items if inventory is full
├─ TradeIndicators         IndicatorProvider "capitaltrade"
├─ gui/                    PortMenu (Export tab, Import tab, Port News)
└─ storage/                own DB, migrations
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
- **Today's quota per item** is `quota-per-active-player × max(1, stats().activePlayersToday)`, using yesterday's figure if today's is still small. **A quiet server gets a small quota, so it can't print money for nobody.**
- **Price slides as the quota fills.** Selling `q` units when `u` have already been exported today:
  ```
  unit price at fill level x = p × (1 − k · x/quota)        k = 0.5   (at a full quota the price is half)
  total = ∫ from u to u+q of that   (computed per stack for display)
  ```
  The amount that can be exported is capped at the quota remaining. The GUI shows "Ships can take 340 more wheat today".
- **Flow** (one transaction, at a Port):
  1. Remove the items from the player's inventory and write a `PENDING` row.
  2. `transaction("trade.export", "Sold 64 wheat to faraway lands").issue(player, total).reference("exp:<id>")`.
  3. If it succeeds, mark it `DONE`. The items are **gone**, because they were exported.
     If it fails, return the items and show a plain message, for example "The ships are full for today!" for `POLICY_LIMIT`.
  4. Startup recovery uses `findByReference`, the same pattern as Market.
- **Why `issue` and not `serverPay`:** export money really is new money entering the server from outside. It must not drain the Treasury that pays for jobs and interest. Core's `issue-per-day` cap for CapitalTrade is the owner's hard limit.
- **Daily export budget:** the smaller of `monetaryPolicy.budget("exports")` (if any provider answers) and `exports.daily-budget` from config. Once it's used up, exports close until tomorrow.

### 5.4 Imports
- **Quota:** the same per-active-player rule as exports.
- **Price rises as the quota fills:**
  ```
  unit price = p × (1 + tariff) × (1 + k · x/quota)
  ```
- **Flow** (one transaction):
  - `transaction("trade.import", "Bought 1 sponge from abroad")`
  - `.move(player → Treasury, tariffPart)`: the tariff stays in the server as government income.
  - `.destroy(player, restPart)`: the rest is paid to foreigners and leaves the economy.
  - `.reference("imp:<id>")`
- **Delivery:** the items go to the player's inventory. If it's full, they go to the player's **Cargo** (collect at any Port).
- **Off by default.** If `imports.yml` is empty, the Import tab is hidden.

### 5.5 Ports
- **Creating a Port:** `/trade admin port create [name]` while looking at a block (it adds a `[Port]` sign), or on a Citizens NPC (adds a Port trait).
- **Using a Port:** the Port GUI opens when right-clicking a Port, or with `/trade` within `ports.radius` (8 blocks) of one.
  - With `ports.anywhere: true`, `/trade` works everywhere. That's off by default, so Ports are destinations, like harbours.
- **The GUI** has three tabs:
  - **Export:** items with ▲/▼ world price, "ships can take N more today", and a Sell button with 1 / 16 / 64 / All.
  - **Import:** shown only if any imports are configured.
  - **Port News:** this week's boom, and today's busiest exports.

### 5.6 `TradeService` (public façade)
```java
Quote quoteExport(ItemKey k, int qty);  CompletableFuture<TradeResult> export(UUID p, ItemKey k, int qty);  // from inventory (or test hook)
Quote quoteImport(ItemKey k, int qty);  CompletableFuture<TradeResult> importItems(UUID p, ItemKey k, int qty);
double worldPrice(ItemKey k);  int quotaLeft(ItemKey k, boolean export);
```
The simulator uses `export`/`importItems` with a test hook that skips the inventory, available only under core's simulator system property.

### 5.7 Indicators (`IndicatorProvider` id `capitaltrade`)
Cached and refreshed after every trade (cheap counters) and at each Day event:
- exports value today, and new money from exports today
- imports value today, tariffs collected today, coins destroyed by imports today
- trade balance (exports minus imports)
- this week's boom item
- quota used, as a percentage

## 6. Data (own database, `plugins/CapitalTrade/trade.db` by default)
```
world_price  (item_key, day_index, price, PK(item_key, day_index))
quota_used   (item_key, day_index, direction EXPORT|IMPORT, qty, PK(item_key, day_index, direction))
shipments    (id PK, uuid, item_key, direction, qty, total, tariff NULL, ts, status PENDING|DONE|FAILED)
booms        (week_index PK, item_key, multiplier)
ports        (id PK, name, world, x, y, z, kind SIGN|NPC, npc_id NULL)
cargo        (uuid, item_key, qty, PK(uuid, item_key))
```

## 7. Config (abridged)
```yaml
storage: { type: sqlite }
world-prices: { theta: 0.10, sigma: 0.05, min-x: 0.5, max-x: 2.0, seed: 12345 }
exports: { slide-k: 0.5, daily-budget: 5000 }
imports: { slide-k: 0.5 }
booms: { chance: 0.5, multiplier: 1.3 }
ports: { radius: 8, anywhere: false }
```
Other files: `exports.yml`, `imports.yml`, `messages.yml`.

## 8. Commands and permissions

| Command | Permission (default) | What |
|---|---|---|
| `/trade` | `capitaltrade.use` (all) | Port GUI (near a Port, unless `anywhere`) |
| `/trade cargo` | `capitaltrade.use` | Collect imported items held for you |
| `/trade admin port create\|remove\|list` | `capitaltrade.admin` (op) | Manage Ports |
| `/trade admin boom <item> [x]` | op | Start a boom now (for events) |
| `/trade admin reload` | op | Reload |

PlaceholderAPI: `%capitaltrade_price_<item>%`, `%capitaltrade_quota_<item>%`, `%capitaltrade_boom%`.

## 9. Threading and failure handling
- **Inventory changes and GUIs:** main/player thread.
- **Money:** async API calls. The result switches back to the player's thread to finish or roll back.
- **Quotas** update in memory under one lock per item, and persist with the shipment.
- **On `POLICY_LIMIT` or a used-up budget:** "The ships are full for today! Try the Market."
- **On `ECONOMY_PAUSED`:** "The Port is closed for a moment."
- **The items always go back.**

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

## 11. Done when
- [ ] On a 2-player server, exports pay a fair-but-low price, quotas stop money printing, and Port News booms are visible.
- [ ] With CapitalMarket installed, cheap Market items get bought and exported (seen in `sim`), which lifts the Market price toward the world price.
- [ ] Imports are off by default. When enabled, they're a measurable money sink and the tariffs reach the Treasury.

## 12. Open questions
1. **Should CapitalMarket's autopilot answer `budget("exports")`,** so exports shrink when prices run hot? *Later.* It's one line in Market once it's wanted, and Trade already reads it.
2. **Should world prices follow Market prices** instead of fixed bases? *No.* Independent world prices are what make arbitrage meaningful.
