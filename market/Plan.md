# CapitalMarket — Plan

> **Artifact:** `CapitalMarket.jar` (optional add-on) · **Depends on:** CapitalEngine ([../core/Plan.md](../core/Plan.md)) via [../api/Plan.md](../api/Plan.md)
> **Parent plan:** [../Plan.md](../Plan.md)

## 0. Build status (what exists, and where the code differs from this plan)
**Built and tested (`./gradlew :market:test`):** everything in §1 except the items under "Not built yet": Quick Sell / Quick Buy / listings / wanted with escrow, the Stash, the server market maker, recipe pricing, two-phase trades with crash recovery, the Jobs Board (delivery, build, project and "the town needs" jobs, project chests), the autopilot with `MonetaryPolicy` + `IndicatorProvider`, Market News (chat and Discord webhook), tutorial, flood/dupe alerts, stalls, chest menus, `/economy`, PlaceholderAPI, and both escrow checks.

**Deliberate differences from the sections below:**
- **One worker thread, no per-item locks and no in-memory order book (§5.3, §9).** Core's ledger group-commits, so what costs time is waiting on it, not matching. `MarketService.execute` therefore writes all PENDING rows in one database transaction, sends every fill's money transaction before waiting on any, and finishes the successes in one more transaction: a 32-stack dump waits once. SQLite has a single writer, so per-item parallelism would only queue on the database; one worker also removes every lock and every "reserve, then roll back" path. The book is read from `orders` through the `(item, side, status, unit_price, id)` index. Today's server spending and per-item buying are kept in memory and reloaded from `trades` when the day changes. If a very busy server ever needs more, shard the worker by item and give each shard its own connection (MySQL/Postgres first).
- **Storage:** SQLite (default), MySQL/MariaDB and PostgreSQL through HikariCP, with `market-db/<dialect>/V<n>__*.sql` migrations and an optional table `prefix` (default `market_` outside SQLite). SQLite keeps `synchronous=FULL` (a PENDING row must survive power loss before its money moves). `StoreDialectTest` runs every statement on SQLite always and on PostgreSQL/MySQL when `CAPITAL_TEST_POSTGRES` / `CAPITAL_TEST_MYSQL` are set.
- **Item keys rebuild exact stacks** (`iron_ingot`, `enchanted_book{sharpness:5}`, `potion{strong_healing}`), so there is no `items` table with a stack BLOB. A stack is tradable only if it equals the stack rebuilt from its key.
- `server-items.yml` lists what the server trades; prices come from `values.yml` (raw materials, never overridden) and `RecipeValuer`. `/market admin revalue` also forgets drift.
- A fresh server starts with `target-stock-default` of every server item (otherwise an empty stock prices at 4x base).
- `orders` and `jobs` have no `escrow_left`: escrow is `Σ remaining × price` of open orders/jobs. `policy_state` has plain columns instead of JSON. `price_daily` is not kept; weekly figures come from `trades`, which is pruned after 8 weeks.
- The same-IP trade filter is not implemented (outliers are cut at 3x the median).
- "The town needs" jobs follow §5.8 literally: delivered items are used up and don't change server stock.
- **Budget pooling:** while no server job is open, the jobs share of the daily budget (60%) is added to the buyer's, so a fresh server can buy more than one expensive item a day. The total new-money allowance is unchanged. Without this a solo player's 60-coin buyer budget could not cover a single 42-coin diamond sale after any other sale.
- **Stalls** place a barrel on top of the block the administrator is looking at, with a text-display hologram above it. They need three clear blocks and never replace the original block. Players cannot break the barrel; `/market admin stall remove` does.
- **Menus** use named buttons with lore for figures, a blank-pane grid, centred layouts, a Sell side (green) and a Buy side (orange) on item pages, Browse with Buy/Sell/stock/trend lore, and an item search (typing a name the market doesn't stock opens that item's player-listings page).
- **Player documentation:** [docs/player-guide.md](docs/player-guide.md) is the full guide (server owners can publish it on their own wiki), and `/market help [topic]` plus a Help button in the main menu give the short version in chat, with clickable topics and a typed fallback for Bedrock. Both are written from the behaviour in this plan; update them together.
- **Floodgate:** Bedrock players get one input form for number entry and a Yes/No form for confirmations (`MarketForms`, the only class that touches Floodgate); everyone else gets chat prompts and chest menus. Orders and jobs expire after a number of economic days, not wall-clock time.
- `/market admin reload` re-reads messages, `values.yml`, `projects.yml` and recipes; other `config.yml` settings need a restart.

**Not built yet:** Citizens NPC stalls and the other items in [../BACKLOG.md](../BACKLOG.md). MySQL/MariaDB is written and unit-tested only against SQLite and PostgreSQL.

## 1. Purpose and scope
CapitalMarket is where prices come from and where most players earn coins. It covers:
1. **The Market:** Quick Sell and Quick Buy, "set my own price" listings and "wanted" requests, a Stash for items, and market stalls.
2. **The server market maker:** the server always buys and sells everyday items around a reference price set by its own stock, so the market works with only one player online.
3. **The Jobs Board:** delivery and build jobs from players and from the server, including owner-defined projects.
4. **The autopilot:** a weekly price check that publishes the `MonetaryPolicy` service (rates and daily budgets) and an `IndicatorProvider`.
5. **Market News, the tutorial, and dupe and flood alerts.**

## 2. Not in scope
- **The bank** (savings and loans). CapitalBank reads this plugin's `MonetaryPolicy` but doesn't need it.
- **The NPC world market with exports and imports.** That's CapitalTrade.
- **Charts for owners.** CapitalDashboard shows this plugin's indicators.
- **Items with custom names, lore, custom model data, partial durability or player heads.** They can't be traded here; player shop plugins handle them.

## 3. Depends on
- **Required:** `CapitalEngine` (API ≥ 1.0).
- **Soft:**
  - `PlaceholderAPI`
  - `Citizens`: NPC stalls
  - `Floodgate`: Bedrock forms
- **No other add-on.** The only thing this plugin knows about another add-on is checking whether `CapitalBank` is enabled (§5.7), purely to decide how to split budgets.

## 4. Package layout and key classes
```
com.patelheet.capitalengine.market
├─ CapitalMarketPlugin
├─ MarketService              public façade used by GUIs, commands and the simulator   (§5.9)
├─ item/
│  ├─ ItemKey                 canonical id: "minecraft:iron_ingot", "minecraft:enchanted_book{sharpness:5}"
│  └─ Tradability             what may be traded (vanilla, no custom data, full durability)
├─ stash/StashService         per-player item storage (escrow for items)
├─ book/
│  ├─ OrderBook               per item: bids/asks, price-time priority          (pure Java)
│  ├─ Matcher                 builds a fill plan incl. the server's virtual quotes (pure Java)
│  └─ TradeExecutor           two-phase: pending row → money tx → finalize/rollback
├─ maker/
│  ├─ ServerMaker             server quotes, stock, daily stock use-up
│  └─ ReferencePrice          stock-based price + base drift                     (pure Java)
├─ value/RecipeValuer         base values from values.yml + Bukkit recipes      (pure Java core + Bukkit adapter)
├─ jobs/
│  ├─ JobsBoard               player & server jobs, delivery, sign-off, expiry
│  └─ ServerJobPlanner        daily server jobs from projects.yml + oversupply
├─ autopilot/
│  ├─ PriceIndex              weekly basket index                                (pure Java)
│  ├─ Autopilot               Appendix A rule → PolicyState                     (pure Java)
│  └─ MarketPolicyService     implements MonetaryPolicy + IndicatorProvider
├─ news/MarketNews
├─ tutorial/Tutorial
├─ alerts/FloodDetector
├─ stall/Stalls               block/sign/NPC stalls
├─ gui/                       MarketMenu, ItemMenu, ListingsMenu, StashMenu, JobsMenu, JobMenu, EconomyMenu
└─ storage/                   own DB (SQLite file by default), migrations, repos
```
The pure-Java parts can be unit tested directly and driven by the simulator.

## 5. How it works

### 5.1 Items
- **`ItemKey`:** the material, plus any components that matter, in a stable sorted form:
  - stored enchantments for enchanted books
  - potion contents for potions and tipped arrows
  - nothing else
- **`Tradability`:** vanilla items only, with no custom name, lore or custom model data, and full durability.
  - Shulker boxes must be empty.
  - Owners can block specific items in `config.yml`.
- **Storage:** each key is stored once in `items` with a canonical `ItemStack` (`serializeAsBytes`), so delivered items are exact vanilla stacks.

### 5.2 Stash (item escrow)
- **Every player has a Stash** that holds items for them. It's limited to `stash.max-stacks` (default 54, one double chest's worth), with no fees.
- **Items move into the Stash** when the player lists them (from their inventory), buys them, or receives them from a job.
- **Each item has `qty` and `locked`:** listed items are `locked` until they sell or the listing is cancelled.
- **Depositing** from the inventory works anywhere.
- **Withdrawing** to the inventory happens at a **stall**. Owners can turn on `stash.withdraw-anywhere` for kid servers.

### 5.3 Order book and matching
- **One `OrderBook` per item,** held in memory and backed by `orders`. Buy orders (bids) are sorted highest price first, then oldest first. Sell orders (asks) are sorted lowest price first, then oldest first.
- **Each item has a lock** (striped). All matching for that item happens under it, so matches are consistent without a global lock.
- **The server's quotes are virtual orders.** They're shown in the book but never stored, and they rank **after players at the same price**, so players always get filled first.
- **Order types:**
  - **Quick Sell** is a market sell for `qty`. It fills the best bids top down, including the server's bid (§5.4), and shows the total before confirming.
  - **Quick Buy** is a market buy with a maximum total shown on the confirmation screen.
  - **Listing** ("set my own price") is a limit order. It matches immediately if it crosses the book; any remainder rests.
  - **Wanted** is a limit buy that rests the same way.
- **Trades happen at the resting order's price.**
- **Limits:**
  - Price per unit ≥ 1 smallest unit.
  - Up to 20 open orders per player (configurable).
  - Orders expire after 14 days and their items or coins are returned.

### 5.4 Money flows (all through the API)

| Action | Coins | Items |
|---|---|---|
| Place a wanted order | `transfer(player → capitalmarket:escrow, qty×price)` | — |
| Cancel or expire a wanted order | `transfer(escrow → player, remaining)` | — |
| List (sell order) | — | Inventory → Stash, `locked += qty` |
| Fill: player sells to a player bid | `transaction.move(escrow → seller, gross−fee).move(escrow → Treasury, fee).reference("fill:<id>")` | Seller's locked items → buyer's Stash |
| Fill: player buys from a player ask | `transaction.move(buyer → seller, gross−fee).move(buyer → Treasury, fee).reference("fill:<id>")` | Same |
| Player sells to the server | `transaction("market.server_buy").serverPay(seller, gross).reference("fill:<id>")` | Seller's items → server stock |
| Player buys from the server | `transaction.move(buyer → Treasury, gross).reference("fill:<id>")` | Server stock → buyer's Stash |

- **Fee:** `market.fee` (default 2%) is charged to the **seller** on player-to-player fills and goes to the Treasury. There's no fee on trades with the server, because the gap is the server's cut.
- **Fills against the server** draw on the `"buyer"` budget (§5.7). When that budget is used up, the server's bid is hidden until the next day.

**`TradeExecutor`, two-phase:**
1. Under the item lock, write a `trades` row with status `PENDING` and adjust `locked` and the stock.
2. Send the money transaction with reference `fill:<tradeId>`.
3. If it succeeds, move the items and mark the trade `DONE`.
   If it fails, roll back the item changes, mark it `FAILED`, and tell the player in plain words.
4. **On startup,** for every `PENDING` trade: call `findByReference(fill:<id>)`. If found, finish the trade; if not, roll it back.

### 5.5 Server market maker
- **Items:** `server-items.yml` lists the items the server deals in. The default is about 150 everyday vanilla items: ores, ingots, crops, food, wood, stone, mob drops and common blocks.
- **Reference price** (`ReferencePrice`):
  ```
  ref   = base × (targetStock / max(stock, 1)) ^ k          k = 0.5, clamped to [0.25×base, 4×base]
  bid   = ref × (1 − spread)      ask = ref × (1 + spread)   spread = autopilot mmSpread (0.30 … 0.50)
  ```
  - **The price is recalculated per stack within one fill,** so dumping 2,000 wheat walks the price down as it goes.
  - `targetStock` defaults to 640 and can be overridden per item.
- **Base price:**
  - It starts from `RecipeValuer` (§5.6).
  - Every Week, if the item has had at least 20 player-to-player trades in the past 4 weeks, `base += 0.1 × (playerVWAP − base)`. Real prices gradually take over.
- **Selling:** the server only sells from its own stock and never creates items. The exception is an owner-set `always-in-stock: true` per item, which is off by default.
- **Daily use-up:** at each Day event, the server "uses" `stock-use-rate` (default 2%) of every stock. That models the server consuming goods, and stops the stock growing forever. Those items are gone, which is an item sink.
- **Bid capacity:** limited by the `"buyer"` budget and `per-item-daily-buy` (default 1,280).

### 5.6 Recipe valuation
- **`values.yml`** lists about 40 raw materials with base prices (logs, cobblestone, iron/gold/diamond/…, wheat, string, …), shipped with sensible defaults.
- **Crafted items:** `RecipeValuer` walks `Bukkit.recipeIterator()`. Each crafted item's value is `Σ ingredient values / output count × (1 + labour)`, where labour is 5% per crafting step.
  - Furnace recipes add a small fuel cost.
  - When an item has several recipes, the cheapest is used.
  - Cycles (e.g. iron block ↔ ingots) are resolved by taking the raw path.
- **It's recalculated** on `/market admin revalue` and whenever `values.yml` changes. The result is cached in `item_values`.

### 5.7 Autopilot (hidden; publishes `MonetaryPolicy`)
At each **Week** event that isn't a catch-up:
1. **`PriceIndex`:**
   - **The basket** is the 25 items with the most traded value over the past 4 weeks, weighted by value share and re-weighted every 4 weeks.
   - **The weekly change** is `p = Σ wᵢ · (VWAPᵢ this week / VWAPᵢ last week) − 1`. Items with no trades this week use the server's `ref`.
   - **Ignored trades:** self-trades, trades between the same IP, and trades more than 3× or less than ⅓ of the 4-week median.
2. **`Autopilot`** applies the root plan's Appendix A rule:
   ```
   error      = p − targetWeekly
   rate       = clamp(rate + clamp(0.5·error, ±0.25pp/week), min, max)
   newMoney   = max(targetGrowth × activeMoneySupply, floorPerActivePlayer × avgActivePlayers7d)   // per day, from api.stats()
              × clamp(1 − 4·error, 0.5, 1.5)
   saveShare  = clamp(0.33 + 4·error, 0.1, 0.7)
   total/day  = newMoney + avgDailyTreasuryIn(last 7 days)   // budgets cover recycled Treasury money too
   budgets    = savings: total·saveShare ; jobs: total·(1−saveShare)·0.6 ; buyer: total·(1−saveShare)·0.4
   mmSpread   = clamp(0.30 + 0.4·playerVolumeShare, 0.30, 0.50)
   ```
   - **If `CapitalBank` isn't enabled**, the savings share is 0 and its budget goes to jobs and the server buyer.
   - **Prices need no population adjustment.** Population is handled by the `floorPerActivePlayer × active players` term.
3. **The new `PolicyState`** is stored in `policy_state` and served by `MarketPolicyService`:
   - `savingsRatePerDay()` and `loanRatePerDay()`
   - `budget("savings" | "jobs" | "buyer")`
   - Any other channel returns `OptionalLong.empty()`.
4. **The `IndicatorProvider`** (`capitalmarket`) publishes:
   - price index
   - weekly inflation %
   - new money per day
   - savings and loan rate
   - `mmSpread`
   - player trade share
   - trades per day
   - server stock value

On a brand-new server with no history, config defaults are used for the first 2 weeks (warm-up).

### 5.8 Jobs Board
- **Delivery job:** *"Bring N × item. Pay X each."*
  - Anyone can deliver part of it from their inventory or Stash.
  - They're paid per unit immediately.
  - Player jobs send the items to the poster's Stash.
- **Build job** (player-posted, optional):
  - One player accepts it. The poster clicks **Done ✔** to release the pay, or **Problem** to call staff.
  - Staff use `/jobs admin settle <id> pay|refund`.
- **Player jobs:**
  - The pay is escrowed when the job is posted: `transfer(poster → capitalmarket:jobs-escrow)`.
  - Deliveries are paid with `transfer(jobs-escrow → deliverer)` and reference `job:<id>:<deliveryId>`.
  - On expiry (default 7 days), any unused escrow is refunded.
- **Server jobs** (`ServerJobPlanner`, at each Day event):
  1. **Projects first.** `projects.yml` holds owner projects, for example:
     ```yaml
     spawn-railway: { name: "Spawn Railway", needs: { iron_ingot: 5000, rail: 2000 }, pay-premium: 0.15, deliver-to: chest@world,10,64,-20 }
     ```
     Items go to the **project chest** (if set) so the owner can actually build with them. Otherwise they're destroyed.
  2. **Then oversupply.** Items whose server stock is more than 2× the target, or whose price fell more than 10% this week, get "The town needs X" jobs. Delivered items are destroyed: they're used up, which is a sink.
  - **Pay per unit** is `ref × (1 + premium)` (default premium 10%). It's more than the server's bid, so players prefer jobs.
  - **Payment** is via `serverPay(deliverer, …, "market.server_job")`, counted against the `"jobs"` budget. When today's budget runs out, server jobs show **"Paused until tomorrow"**.

### 5.9 `MarketService` (public façade)
```java
Quote quoteSell(UUID p, ItemKey k, int qty);  Quote quoteBuy(UUID p, ItemKey k, int qty);
CompletableFuture<FillResult>  quickSell(UUID p, ItemKey k, int qty);                // from Stash
CompletableFuture<FillResult>  quickBuy (UUID p, ItemKey k, int qty, long maxTotal);
CompletableFuture<OrderResult> list     (UUID p, ItemKey k, int qty, long unitPrice);
CompletableFuture<OrderResult> wanted   (UUID p, ItemKey k, int qty, long unitPrice);
CompletableFuture<Boolean>     cancel   (UUID p, long orderId);
StashService stash();  JobsBoard jobs();  Optional<PriceInfo> price(ItemKey k);
```
GUIs, commands and the simulator all go through this façade. The simulator also uses `stash().testDeposit(...)`, which is only available when the core's simulator system property is set.

### 5.10 Market News (players)
At each Week event, after the autopilot runs, one broadcast goes out (and to a Discord webhook if `news.discord-webhook` is set).
- **Content:** the 2 biggest risers and 1 faller from the basket (each with at least 64 traded), plus the savings rate and an active project.
- **Templates** in `messages.yml` use plain words, for example: *"📰 Market News: Iron is up 18% this week. The railway job needs lots of it! Wheat is down 10%. Savings rate is now 2% a week."*

### 5.11 Tutorial
There are 5 steps, tracked in `tutorial`:
1. Open `/money`
2. Quick Sell anything
3. Deliver to any job
4. Open `/economy`
5. Open `/bank` (shown only if `CapitalBank` is enabled; otherwise skipped)

- Each step pays `tutorial.reward` (default 20) via `serverPay` with reference `tut:<uuid>:<step>`.
- A boss bar shows the current step, and the tutorial can be skipped with `/market tutorial skip`.

### 5.12 Flood and dupe alerts
`FloodDetector` keeps each player's 14-day average daily sell quantity per item.
- **One player:** if today's quantity is more than 10× their average and more than 256, it raises `api.alert(WARN, "market.item_flood", "Sam sold 3,200 diamonds today (usual: 40)")`.
- **The whole server:** a jump of more than 5× in an item's supply raises `market.supply_spike`.

## 6. Data (own database, `plugins/CapitalMarket/market.db` by default)
```
items         (item_key PK, material, stack BLOB)
item_values   (item_key PK, base, source ENUM(recipe,config,drift), updated_day)
stash         (uuid, item_key, qty, locked, PK(uuid,item_key))
orders        (id PK, item_key, side BUY|SELL, owner UUID, unit_price, qty, filled, escrow_left, created, expires, status OPEN|DONE|CANCELLED|EXPIRED)
trades        (id PK, item_key, buyer UUID NULL, seller UUID NULL, server_side BUY|SELL|NULL, unit_price, qty, fee, ts, status PENDING|DONE|FAILED)
server_stock  (item_key PK, qty)
price_daily   (day_index, item_key, vwap, volume, value, player_volume, PK(day_index,item_key))
policy_state  (week_index PK, json)                 -- rate, budgets, mmSpread, index, inflation
jobs          (id PK, poster UUID NULL(server), kind DELIVERY|BUILD, item_key NULL, qty_total, qty_done, pay_unit, escrow_left, project NULL, assignee NULL, status, created, expires)
job_deliveries(id PK, job_id, uuid, qty, paid, ts)
stalls        (id PK, world, x, y, z, kind BLOCK|NPC)
tutorial      (uuid PK, step, done BOOL)
flood_stats   (uuid, item_key, avg_daily, today, PK(uuid,item_key))
```
**Escrow check,** run at each Day event and on `/market admin check`:
- `capitalmarket:escrow` balance = Σ `orders.escrow_left` (open buy orders)
- `capitalmarket:jobs-escrow` balance = Σ `jobs.escrow_left`

A mismatch raises a `CRITICAL` alert.

## 7. Config (abridged)
```yaml
storage: { type: sqlite }                 # or mysql/postgres with own table prefix
market: { fee: 0.02, max-open-orders: 20, order-expiry-days: 14, blocked-items: [] }
stash: { max-stacks: 54, withdraw-anywhere: false }
maker: { k: 0.5, target-stock-default: 640, stock-use-rate: 0.02, per-item-daily-buy: 1280, always-in-stock: [] }
jobs: { player-job-expiry-days: 7, server-premium: 0.10, oversupply-factor: 2.0 }
autopilot:
  target-monthly-inflation: 0.02           # → weekly target
  target-growth-monthly: 0.03
  floor-per-active-player: 150             # coins/day of new money per active player
  rate: { start-weekly: 0.02, min-weekly: 0.0, max-weekly: 0.08 }
  warmup-weeks: 2
  split-jobs-vs-buyer: 0.6
news: { enabled: true, discord-webhook: "" }
tutorial: { enabled: true, reward: 20 }
alerts: { flood-multiplier: 10, flood-min: 256 }
```
Other files: `values.yml`, `server-items.yml`, `projects.yml`, `messages.yml`.

## 8. Commands and permissions

| Command | Permission (default) | What |
|---|---|---|
| `/market` | `capitalmarket.use` (all) | Market GUI |
| `/market sell [qty]` | `capitalmarket.use` | Quick Sell the item in hand (with confirmation) |
| `/market price [item]` | `capitalmarket.use` | Price, ▲/▼ and "usually sells for" |
| `/jobs` (or `/jobboard`) | `capitalmarket.jobs` (all) | Jobs Board GUI; post, deliver, mark done |
| `/economy` | `capitalmarket.economy` (all) | Prices and trends page |
| `/market tutorial skip` | all | Skip the tutorial |
| `/market admin stall create\|remove` | `capitalmarket.admin` (op) | Stalls |
| `/market admin project create\|remove\|list` | op | Projects (also via `projects.yml`) |
| `/market admin stock <item> <qty>` | op | Seed the server's stock |
| `/market admin revalue` / `check` / `reload` | op | Maintenance |
| `/jobs admin settle <id> pay\|refund` | `capitalmarket.jobs.admin` (op) | Settle build-job disputes |

If another plugin already owns `/jobs`, the command is registered as `/jobboard` (detected at enable).

PlaceholderAPI: `%capitalmarket_price_<item>%`, `%capitalmarket_trend_<item>%` (▲3%), `%capitalmarket_savings_rate%`.

**GUIs** use big icons, numbers with the coin symbol, and "Sell 64 iron for 192 coins? ✔ / ✖" confirmations. Amounts are picked with the buttons 1 / 16 / 64 / All. Chest GUIs work for Bedrock players through Geyser. Text and number entry uses a chat prompt, or a Floodgate form when installed; anvil inputs are never used.

## 9. Threading and failure handling
- **Matching** happens under per-item locks on the calling thread, which is the main thread for GUIs.
- **Money calls** are async through the API. GUI updates switch back to the player's thread.
- **Database writes** go through the plugin's own single writer, the same pattern as core but smaller.
- **API failures:**
  - `POLICY_LIMIT` or a budget running out → "The server can't buy more of this today, try a player listing!"
  - `ECONOMY_PAUSED` → "The market is taking a short break."
  - Either way the trade is rolled back (§5.4).
- **The autopilot never runs during catch-up events.** It only runs at a live Week boundary.

## 10. Tests
- **Unit tests** (pure Java):
  - `OrderBook` and `Matcher`: price-time priority, players before the server at the same price, partial fills, server bid hidden when the budget is gone
  - `ReferencePrice`: monotonic in stock, clamped, price walks within a fill
  - `RecipeValuer`: cycles, cheapest recipe, furnace cost
  - `PriceIndex`: re-weighting, outlier and self-trade filters
  - `Autopilot`: each branch of the rule, warm-up, no-Bank split
- **`TradeExecutor`:** crash after the pending row (recovers by reference both ways), money failure rolls the items back.
- **Escrow check** after randomised order, cancel and fill sequences.
- **MockBukkit:** GUI confirm flow, stall withdraw rule, `/jobboard` fallback, tutorial steps and their idempotent rewards.

## 11. Done when
- [ ] With one player online, Quick Sell and Quick Buy work, and prices move as the server's stock changes.
- [ ] Two players can trade by listings while offline from each other.
- [ ] Server jobs from `projects.yml` fill the project chest, and pay stops at the daily budget.
- [ ] The weekly autopilot publishes a `MonetaryPolicy` that CapitalBank picks up with no code dependency.
- [ ] Market News reads naturally. The tutorial completes, and its rewards can't be farmed.
- [ ] Both escrow checks pass after a simulated month (see `sim`).

## 12. Open questions
1. **Stall style default:** a sign on a barrel, or a Citizens NPC when installed? *Recommend the sign on a barrel by default, and an NPC when Citizens is installed.*
2. **Should Quick Sell be allowed from the inventory with no stall at all?** *Yes* (selling is the kid-critical path). Only withdrawals need a stall.
3. **Should the Stash have a storage fee** as an extra sink? *Not by default.* The cap is enough.
