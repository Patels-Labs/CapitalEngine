# CapitalEngine — Plan

A modular Minecraft economy. **CapitalEngine** is a small, trustworthy core: one currency, a ledger where every coin is tracked, and an API. Everything else (market, jobs, bank, dashboard) is a separate add-on plugin built on that API, and other developers can build on it too.

> **The idea in one line:** money always comes from somewhere, players set the prices, and the server quietly keeps things stable. Players don't need to understand any of that to enjoy it.

---

## 1. What's wrong with most economy plugins

- **Admin sell shops** buy unlimited items at fixed prices. Every AFK farm becomes a money printer, prices never move, and players never need to trade with each other.
- **Voting, real-money and "jobs" rewards** create money from nothing. Nobody tracks it, prices climb forever and new players can't catch up.
- **Owners can't see anything.** Nobody knows how much money exists, where it came from or who is duping.

## 2. The ecosystem

| Plugin | Required? | Owns | Player commands |
|---|---|---|---|
| **CapitalEngine** (core) | **Yes** | Ledger, Mint and Treasury, `/money`, `/pay`, Vault and VaultUnlocked adapters, per-plugin accounts and policy, balance import, audits, **the API** | `/money` (`/bal`), `/pay` |
| **CapitalMarket** | No | Market, Jobs Board, autopilot, Market News, tutorial | `/market`, `/jobs`, `/economy` |
| **CapitalBank** | No | Savings, simple loans, land upkeep | `/bank` |
| **CapitalDashboard** | No | Owner dashboard (in game and web), alerts | *(owner only)* `/cdash` |
| **CapitalTrade** | No | NPC world market at Ports: exports with quotas, opt-in imports with tariffs, Port News | `/trade` |
| **Future add-ons** | No | Businesses, Stocks, Cash, Nations, … (§11) | Their own |

```
            ┌──────────────┐ ┌─────────────┐ ┌──────────────────┐ ┌───────────────────┐
            │ CapitalMarket│ │ CapitalBank │ │ CapitalDashboard │ │ any 3rd-party     │
            └──────┬───────┘ └──────┬──────┘ └────────┬─────────┘ │ plugin (API/Vault)│
                   └────────────────┴────── API ──────┴───────────┴─────────┬─────────┘
                                         ┌──────────────────────────────────▼──┐
                                         │ CapitalEngine core: ledger, Mint,   │
                                         │ Treasury, per-plugin policy, Vault  │
                                         └─────────────────────────────────────┘
```

- **Core alone is a complete economy plugin,** a drop-in replacement for EssentialsX Eco or CMI. Owners install add-ons only for the features they want.
- **The main add-ons never depend on each other.** For example, CapitalBank works with or without CapitalMarket (§5.3). The only exception is a future add-on that is literally built on another one, such as Stocks on Business or Banks+ on Bank.
- **Add-ons only talk to core through the API.** They never touch the database or another add-on's internals. If an add-on can't do something through the API, the API grows; no shortcuts.

## 3. Design rules for simplicity

1. **Plain words in anything a player reads.** Players see "Market fee", "Savings rate" and "Prices this week", never "VAT", "M1" or "CPI".
2. **Every feature works from a GUI button.** Commands are shortcuts, not requirements.
3. **You can't lose more than you have.** There's no debt spiral by default.
4. **Confirm before anything costs money.** "Sell 64 iron for 192 coins? ✔ / ✖"
5. **The realistic machinery is hidden.** It runs automatically, and curious players can peek with `/economy`.
6. **Advanced means opt-in.** If a plugin or setting isn't installed or enabled, players never see it.

---

## 4. Core: CapitalEngine

### 4.1 The ledger
- **Double-entry.** Every transaction moves coins out of some accounts and into others, and always sums to zero. Coins can't appear from nothing.
- Amounts are `long` whole numbers of the smallest unit (2 decimals by default), so there are no rounding errors.
- Every transaction is one database transaction. It's tagged with a **type**, a **memo**, the **actor** and the **plugin** that caused it.
- Balances are cached in memory, because Vault reads must be instant. Writes go through one background writer thread (`ponytail:` one writer for everything; shard by account if it ever bottlenecks).
- **Nothing is ever deleted.** Mistakes are fixed with a reversing transaction (`/ce reverse <tx>`).
- **A nightly self-check** confirms the books balance and alerts the owner if they don't.

### 4.2 Two special accounts
- **Mint** is the only place money is created or destroyed.
  - Creating coins moves them *Mint → someone*; destroying them moves them *someone → Mint*.
  - The Mint's negative balance **is** the total amount of money on the server, known exactly at all times.
- **Treasury** is the server's wallet.
  - Fees and upkeep paid by players go in.
  - Server jobs, interest and server purchases come out.
  - Money sitting in the Treasury isn't circulating among players.

### 4.3 Per-plugin accounts and policy
Every plugin that moves money gets its own tracked account. That includes old Vault plugins such as Jobs, admin shops and vote rewards, CapitalEngine add-ons, and any third-party plugin using the API.

For each plugin, the owner sets limits in `config.yml`. **This is the owner's hard safety net, enforced by core no matter what an add-on asks for:**

```yaml
plugins:
  CapitalMarket: { issue-per-day: 20000, treasury-per-day: 50000 }
  CapitalBank:   { issue-per-day: 30000, treasury-per-day: 20000 }     # loan-book-max lives in CapitalBank's own config
  CapitalTrade:  { issue-per-day: 5000 }
  Jobs:          { vault-mode: treasury, issue-per-day: 5000 }        # old Vault plugin: paid from the Treasury, capped
  VoteRewards:   { vault-mode: issue, issue-per-day: 1000 }           # allowed to create money, capped
  SomeOldShop:   { vault-mode: deny }
```

Vault plugins with no counterparty (`depositPlayer(player, 50)`) are attributed by vault2's `pluginName`, or by walking the call stack for legacy calls. The owner chooses for each plugin whether that money is **newly created**, **paid by the Treasury**, **capped** or **denied**. Owners can finally see which plugin is printing money.

**Unknown plugins default to `issue`, uncapped, but logged.** This is "observe first": installing core never breaks a server, and the owner tightens limits once `/ce audit top` or the Dashboard shows who prints what. The details are in [core/Plan.md §5.4–5.5](core/Plan.md).

### 4.4 Commands
| Command | Who | What |
|---|---|---|
| `/money` (`/bal`) | Player | Shows coins (GUI with recent history) |
| `/pay <player> <amount>` | Player | Gives coins to a friend |
| `/ce audit <player\|plugin\|tx>` | Owner | Every coin movement, searchable |
| `/ce reverse <tx>` | Owner | Undoes a transaction with a reversing entry |
| `/ce import <provider>` | Owner | Imports balances from the current Vault economy (EssentialsX, CMI, TNE, …) as opening balances |
| `/ce policy` | Owner | Shows and edits per-plugin limits |

Placeholders (PlaceholderAPI): `%capital_balance%`, `%capital_balance_formatted%`.

### 4.5 Vault compatibility (VaultUnlocked + original Vault)

CapitalEngine **is** the economy provider. It owns the balances, so it can't run on top of another economy plugin.

| Server has | Core registers | Other plugins get |
|---|---|---|
| **Vault** (original) | `net.milkbowl.vault.economy.Economy` | Player balances and legacy name-based "bank" methods (mapped to shared accounts once a Businesses add-on exists) |
| **VaultUnlocked** | The legacy interface above **and** `net.milkbowl.vault2.economy.Economy` | All of the above, plus exact decimal amounts, UUID and shared accounts, and multi-currency (for a future Nations add-on) |
| Neither | Nothing | CapitalEngine and its add-ons still work |

**How:**
- `compileOnly` against `net.milkbowl.vault:VaultUnlockedAPI` (2.15 from the CodeMC repo, the latest published there; it contains both the legacy and the vault2 interfaces). It is not shaded. `plugin.yml` has `softdepend: [Vault, VaultUnlocked]`.
- **Always register the legacy provider.** Then, only if `Class.forName("net.milkbowl.vault2.economy.Economy")` succeeds, register the vault2 adapter. The adapter lives in its own class so a Vault-only server never loads it.
- Legacy `double` amounts are rounded half-even to the smallest unit.
- Checked in Phase 0 against the 2.15 jar: every vault2 method takes the calling `pluginName` first, and the shared-account permission enum is `AccountPermission`.

---

## 5. The CapitalEngine API

Published as a separate `capitalengine-api` jar. Add-ons and third-party plugins use `compileOnly` against it and `depend: [CapitalEngine]`. Core registers it with Bukkit's `ServicesManager`, so there's no custom loader.

### 5.1 Surface (deliberately small)

```java
CapitalApi api = Bukkit.getServicesManager().load(CapitalApi.class);

Account player = api.accounts().player(uuid);
Account escrow = api.accounts().plugin(this, "escrow");          // namespaced: capitalmarket:escrow

// Player-to-player or player-to-plugin: atomic, async, attributed to this plugin
api.transfer(this, player, escrow, 500, "market.order", "Buy order #812");   // CompletableFuture<TxResult>

// Multi-leg (e.g. a trade with a fee), all-or-nothing
api.transaction(this, "market.trade", "Trade #4410")
   .move(escrow, seller, 980).move(escrow, api.treasury(), 20).commit();

// Server money: Treasury first, newly created coins for any shortfall, within this plugin's limits
api.serverPay(this, player, 120, "bank.interest", "Weekly savings interest");
api.transaction(this, "bank.interest", "Daily savings interest")             // same, but idempotent
   .serverPay(savings, 12).reference("int:" + day + ":" + uuid).commit();
api.collect(this, player, 15, "bank.upkeep", "Land upkeep");     // player → Treasury

// Raw create/destroy, for loans
api.issue(this, player, 1000, "bank.loan", "Loan #77 payout");
api.destroy(this, player, 1000, "bank.loan", "Loan #77 repaid");

api.stats();   // money supply, active money supply, created/destroyed today, flows by plugin and type
```

- **Escrow is just a transfer** into the plugin's own account. There's no special hold system.
- **Events:**
  - `CapitalTransactionEvent` fires asynchronously after each commit.
  - `CapitalDayEvent` and `CapitalWeekEvent` are **one shared economic clock** that every add-on uses.
  - `CapitalAlertEvent` lets any plugin raise an alert for owners.
  - `CapitalAuditEvent` and `CapitalPolicyLimitEvent` report the nightly check result and plugins hitting their limits.
- **`IndicatorProvider` service:** add-ons publish named numbers (price index, loan book, …). The Dashboard shows them without depending on any add-on.
- **References:** every transaction can carry an idempotency `reference`, so add-ons can retry safely and recover after crashes.
- **Full contract:** [api/Plan.md](api/Plan.md).
- **Policy:** every call is checked against §4.3. A call over its limit fails with a clear `TxResult`, and add-ons show the player a friendly message.
- **Versioning:** the API follows semantic versioning, and each add-on declares the minimum API version it needs. Breaking changes only happen in major versions.

### 5.2 Players who've left
Core doesn't move their money. `stats().activeMoneySupply()` simply **excludes balances of players inactive for 60+ days** (configurable). Add-ons base their calculations on the active figure, so money left by quitters doesn't distort anything. When those players return, it all just counts again.

### 5.3 Shared economic settings without add-ons depending on each other
The API defines a `MonetaryPolicy` interface, but **core doesn't implement it** (core stays free of economics). It provides the savings rate, loan rate and today's budget for each channel where new money can come from:

- **If CapitalMarket is installed,** its autopilot registers a `MonetaryPolicy` service.
- **If not,** CapitalBank uses the fixed rates in its own config.

So Bank never depends on Market; it just gets smarter when Market is present. Any future add-on reads policy the same way.

---

## 6. Where money comes from

Money creation is **allowed**, just like in real life. Real money mostly comes from two sources: banks creating it when they lend, and governments spending more than they collect. CapitalEngine does the same, but every channel has a budget, a named plugin and a record in the ledger.

### 6.1 Channels

| Money comes in through | Real-world equivalent | Plugin | Limited by |
|---|---|---|---|
| **Loans:** created on payout, destroyed on repayment | Bank lending creates money | CapitalBank | `loan-book-max` in CapitalBank's config, and core's `issue-per-day` for the plugin |
| **Savings interest** | Central bank paying interest on reserves | CapitalBank | Daily budget, interest cap per player |
| **Server buying items** in the market | Central bank buying assets | CapitalMarket | Daily budget, stock levels |
| **Server jobs** | Government spending | CapitalMarket | Daily budget |
| **Starter coins** | Stimulus payment | Core (config) | One-off per new player |
| **Exports** to the NPC world market | Foreign money from exports | CapitalTrade | Daily quota per active player, daily budget |
| **Old Vault plugins** set to `issue` | Unlicensed printing! | Any | Daily cap (§4.3) |

| Money goes out through | Where it goes |
|---|---|
| Market fees, land upkeep | Treasury |
| Players buying from the server's market stock | Treasury |
| Loan repayments (principal) | Destroyed (Mint) |
| Loan interest | Treasury |
| Imports (opt-in) | Tariff to the Treasury, the rest destroyed (paid to "foreigners") |

### 6.2 Treasury first, then create money
Every server payment goes through `serverPay`, which **spends real income in the Treasury first** and only creates new coins for any shortfall, all within the plugin's limits. This one rule answers "where does interest come from?" for every server size:

- **Tiny or quiet server:** fees and upkeep are close to zero, so interest and server jobs are paid mostly with new money. The amounts are small because they're capped per active player.
- **Big busy server:** fees, upkeep and loan interest fill the Treasury, so most payouts are recycled money and little new money gets created.

### 6.3 How much new money per day
The autopilot (or a fixed number in config, if CapitalMarket isn't installed) sets one daily total:

```
dailyNewMoney = max( targetGrowth × activeMoneySupply ,  floorPerActivePlayer × activePlayersToday )
```

- **Scales with active players.** A server with nobody online creates nothing, and a server with 3 players creates enough for 3 players.
- **Interest is capped per player.** Interest is only paid on the first N coins in savings (default 10,000), so rich players can't compound forever.
  - *Example:* 3 players each saving 10,000 at 1% per week creates **300 coins a week** in total. That's noticeable for them, and harmless for the economy.

### 6.4 How the autopilot steers: the mix, not just the amount
Once a week, CapitalMarket's autopilot compares prices with last week (using a basket of commonly traded items) and adjusts.

| Prices… | Daily new money | Where it goes | Savings and loan rates |
|---|---|---|---|
| **Rising too fast** | Shrinks | More to **savings interest** (money that sits still) | Up a little |
| **On target** | Steady | Normal split | Steady |
| **Falling** (farms flooding the market) | Grows | More to **server jobs and the server buyer** (money that gets spent, and surplus items get used up) | Down a little |

This is how real central banks cool things down: they reward saving and make borrowing pricier. Because the daily total is capped, raising the savings rate never turns into extra printing. Moves are small and gradual, and the formula is in Appendix A.

---

## 7. Liquidity: making the market work with 1 player or 500

The worry is that if few players are online, nobody's there to trade with. The answer has five parts.

### 7.1 The server is a market maker, not just a buyer
For each everyday item, the server (CapitalMarket) always posts two prices:
- it **buys** from players at about **70%** of the item's reference price
- it **sells** to players at about **130%**

**Players beat the server by trading with each other** anywhere in that gap. A player selling iron to another player at 100% earns more than selling to the server, and the buyer pays less. The gap gives players a built-in reason to trade with each other, while the server guarantees there's always *someone* to trade with.

### 7.2 Prices move even with one player: stock-based pricing
The server's reference price for an item depends on **how much of it the server is holding**:

```
reference = base × (targetStock / max(stock, 1)) ^ k        (clamped to 0.25× … 4× base)
```

- If everyone dumps wheat on the server, its stock rises and the wheat price falls.
- If nobody has sold diamonds lately, stock is low and the diamond price rises, which rewards whoever goes mining.
- **The base price** comes from the item's crafting recipe. Owners set about 40 raw-material values, or use the defaults. Once enough player trades exist, the base drifts toward real trade prices.

### 7.3 The server only sells what it has
The server **never creates items**. It can only sell what players have sold to it, just like a real dealer. Owners can mark specific items as "always in stock" (for example, a rare item on a server without that biome), which acts as a price ceiling. That's off by default.

### 7.4 You don't need to be online at the same time
**Sell listings and "wanted" requests stay up while you're offline.** A player in Europe lists iron, a player in America buys it six hours later, and the coins are waiting when the first player logs in. This works for the Jobs Board too.

### 7.5 The server steps back as players step up
As player-to-player trading grows, the server's **gap widens automatically**, for example from 70/130 to 50/150, and its daily buying budget shrinks. On a big server it becomes a rarely-used safety net. On a small one it stays the main trading partner.

### 7.6 What this looks like at different sizes

| | 2 players | 20 players | 200 players |
|---|---|---|---|
| **Market** | Mostly trading with the server; prices move with server stock | Players undercut the server inside the gap; offline listings matter | Mostly player-to-player; server gap wide and rarely used |
| **Jobs Board** | Mostly server jobs | Mix of server and player jobs | Mostly player jobs; server jobs are big public projects |
| **Savings interest paid by** | Mostly new money (tiny amounts) | Mix | Mostly Treasury income |
| **New money per day set by** | `floorPerActivePlayer` | Either | `targetGrowth × supply` |

---

## 8. CapitalMarket add-on

Market, Jobs Board, autopilot and Market News live in one plugin, because the autopilot needs the market's prices.

### 8.1 Market (`/market`)
- **Quick Sell / Quick Buy.** Pick an item, choose an amount, and see the price before confirming. It fills at the best price available, whether from another player or the server.
- **"Set my own price" tab.** List items for sale or post a "wanted" request at your own price. Underneath this is a real buy/sell order book, but players never see that term.
- **Item price tags.** "Usually sells for ~X" with a ▲/▼ arrow for this week.
- **Items and coins are held safely** in the plugin's escrow account until a trade completes. Trades can't be scammed or duped.
- **Market fee:** 2% (configurable), paid into the Treasury.
- **Picking up and dropping off items:**
  - Players can buy, sell and list from anywhere.
  - Physically handing over or collecting items happens at **market stalls** (a block or NPC), which keeps spawn lively.
  - Owners can switch on use-anywhere.
- **Dupe alerts:** strange item floods, such as one player suddenly selling 10× their usual diamonds, are reported to owners.

### 8.2 Jobs Board (`/jobs`, or `/jobboard` if a Jobs plugin already uses `/jobs`)
- **Delivery jobs**, such as "Bring 200 oak logs, earn 300 coins". They're posted by players or by the server.
- **Server jobs** supply public projects like the spawn railway or the town farm. The items delivered are **used up**, which removes surplus farmed items from the economy. They're paid through `serverPay`.
- **Pay is locked in when a job is posted,** so nobody can skip paying a player.
- **Player-posted build jobs** are optional. The poster ticks "done" to release the pay, and admins settle disagreements.

### 8.3 Autopilot (hidden)
- **Weekly price check.** The basket is the 20–30 most-traded everyday items, weighted by how much of each trades. Trades with yourself, between linked accounts or at silly prices are ignored.
- **Sets** daily new money, how it's split across channels, and the savings and loan rates (§6.4). It publishes these as the `MonetaryPolicy` service.
- **Fades** the server market maker as player-to-player volume grows (§7.5).

### 8.4 Market News and the tutorial
- **Market News:** one friendly chat message a week, with optional DiscordSRV posting.
  > 📰 **Market News:** Iron is up 18% this week. The railway job needs lots of it! Wheat is down 10%. Savings rate is now 2%.
- **Tutorial:** a 5-step tutorial on first join: check your money, sell something, take a job, look at prices, and (if CapitalBank is installed) save some coins. Each step gives a small reward.
- **`/economy`:** prices and trends for curious players.
- **Placeholders:** `%capitalmarket_price_<item>%`, `%capitalmarket_trend_<item>%`.

## 9. CapitalBank add-on (`/bank`)

- **Savings.** Coins in the bank earn a little interest each day, shown as "Savings rate: 2% per week".
  - Interest is only paid on the first 10,000 coins (configurable).
  - It's paid through `serverPay`, so it comes from the Treasury first and new money second.
- **One simple loan.**
  - Players borrow a small amount and pay it back over a few days.
  - The principal is **created when paid out and destroyed when repaid**, exactly how bank lending works in real life. Interest goes to the Treasury.
  - The **limit grows** with each on-time repayment.
  - A missed payment **only pauses borrowing**. There are no debt collectors and no seized items by default.
  - Repayment comes automatically from incoming coins, and a player can never go below zero.
  - A loan that's never repaid is written off and recorded, and its coins count as created money.
- **Land upkeep.** "Your claim costs 15 coins/day", paid into the Treasury, with integrations for GriefPrevention, Lands and WorldGuard (Towny already charges its own upkeep through Vault). It makes giant unused claims cost something.
  - If a player can't pay, the default is a warning, then the claim is frozen (no new building). It's never deleted.
- **Rates** come from CapitalMarket's `MonetaryPolicy` when it's installed, and from fixed config values when it isn't.
- **Placeholders:** `%capitalbank_savings%`, `%capitalbank_rate%`, `%capitalbank_loan_left%` (`%capitalbank_loan%` also works), `%capitalbank_upkeep_due%`.

## 10. CapitalDashboard add-on (owners only)

- **Read-only:** built entirely on `stats()` and `CapitalTransactionEvent`, so it never changes anything.
- **In game:** `/cdash` shows money supply, money created and destroyed today, and the top money-creating plugins.
- **Web:** a simple page served by the JDK's built-in `HttpServer` with no extra dependencies. It shows:
  - charts of money supply over time
  - money created and destroyed, by channel and by plugin
  - Treasury in and out
  - prices and rates, if CapitalMarket is installed
- **Optional** Prometheus `/metrics` endpoint for owners who already run Grafana.
- **Alerts** go to console, in-game staff and Discord: failed ledger self-check, a plugin hitting its cap, unusual money creation, and market dupe alerts.

## 11. Future add-ons (separate plugins, built when owners ask)

| Plugin | Adds |
|---|---|
| **CapitalBusiness** | Shared business accounts with members and roles, payroll, simple profit and loss books. Backs Vault's shared-account and bank methods. |
| **CapitalStocks** | Businesses sell shares that players can trade and receive dividends from. Needs CapitalBusiness. |
| **CapitalBanks+** | Player-run banks with deposits, own rates and loans, backed by reserves. Needs CapitalBank. |
| **CapitalBonds** | Players lend to the server for a fixed return, which gives the autopilot another lever. |
| **CapitalCash** | Coins and cheques as physical items with serial numbers. They can drop on death, which suits PvP, and duplicated copies are caught. |
| **CapitalNations** | Each Towny, Factions or Lands nation gets its own currency, with exchange rates set by trading. Requires VaultUnlocked. |
| **CapitalTax** | Income tax brackets, business tax, optional wealth tax. |
| **CapitalCredit** | Visible credit score, collateral, debt collection and bankruptcy for hardcore servers. |

**Presets are just which jars you install:**
- **Simple:** core, CapitalMarket and CapitalBank. Add CapitalTrade on small servers that want an always-open place to sell.
- **Realistic:** Simple, plus CapitalBusiness, Stocks, Bonds, Tax and Credit.

Each add-on also has its own `simple` or `realistic` config defaults.

## 12. Why server owners want it

1. **Inflation runs itself.** Owners set a target, not hundreds of shop prices.
2. **Every coin is accounted for.** Owners can see exactly which plugin created how much money, and cap it.
3. **Dupe-proof money and dupe alerts** for items.
4. **Full history with clean undo,** through `/ce audit` and `/ce reverse`.
5. **Owners install only what they want.** Core alone already beats a basic economy plugin.
6. **It works on a 2-player server and a 500-player server** (§7.6).
7. **Safe for young players by default,** with as much depth as an owner wants through add-ons.
8. **Developers get an open API,** so the ecosystem can grow beyond the user's own add-ons.
9. **Easy switch:** `/ce import` brings balances over from the current economy plugin. No real-money hooks are built in, so owners stay responsible for following Mojang's guidelines.

## 13. A day on the server (example)

> Sam logs in after school. Nobody else is online, so Sam **Quick Sells** iron to the server at its buying price and also lists some at a higher price. That evening Mia logs in, sees Sam's listing is cheaper than the server's selling price, and buys it. Sam gets the coins while offline. Mia uses the iron for a **server job**: "Supply 500 iron for the spawn railway, 2,000 coins". The Treasury pays her, mostly from this week's market fees, topped up with a little new money. She puts half in the **bank**. On Sunday, **Market News** says *"Iron is up 18% this week. The railway job needs lots of it!"*

Nobody touched an admin shop or read a chart, yet every coin in that story has a source in the ledger.

---

## 14. Repo and build layout

One monorepo, with one Gradle subproject per published jar:

```
CapitalEngine/
├─ settings.gradle.kts
├─ api/         → capitalengine-api.jar   (published to a Maven repo for third parties)
├─ core/        → CapitalEngine.jar
├─ market/      → CapitalMarket.jar
├─ bank/        → CapitalBank.jar
├─ dashboard/   → CapitalDashboard.jar
├─ trade/       → CapitalTrade.jar
└─ sim/         → test-only: headless simulator
```

### Detailed plans
Each subproject has its own `Plan.md` covering scope, classes, flows, data, config, commands, failure handling, tests and a "done when" checklist:

| Subproject | Plan |
|---|---|
| API (the contract everything builds on) | [api/Plan.md](api/Plan.md) |
| Core (ledger, Vault, policy, clock, owner tools) | [core/Plan.md](core/Plan.md) |
| Market (market, market maker, jobs, autopilot, news) | [market/Plan.md](market/Plan.md) |
| Bank (savings, loans, land upkeep) | [bank/Plan.md](bank/Plan.md) |
| Dashboard (snapshots, alerts, web) | [dashboard/Plan.md](dashboard/Plan.md) |
| Trade (Ports, exports, imports) | [trade/Plan.md](trade/Plan.md) |
| Sim (headless simulator, CI gate) | [sim/Plan.md](sim/Plan.md) |

If a sub-plan and this overview disagree, the sub-plan wins. Fix this overview when that happens.

- **Stack:** Paper **1.21.11 → 26.2** (one jar, compiled against 1.21.11 with Java 21 bytecode), Gradle (Kotlin DSL), Adventure/MiniMessage, chest GUIs that also work for **Bedrock players via Geyser**, plus Floodgate forms when installed. If a feature can't work on Bedrock, Java comes first. Paper only; Folia support can come later. Development rules live in [CLAUDE.md](CLAUDE.md).
- **Dependencies:**
  - Add-ons use `compileOnly(project(":api"))` and `depend: [CapitalEngine]`.
  - Core implements the API. HikariCP and the database drivers load through `plugin.yml` `libraries:` (Paper's library loader), so nothing is shaded.
- **Storage:**
  - **Core owns the ledger database:** SQLite by default, or MySQL/MariaDB/PostgreSQL. Schema migrations are plain numbered `.sql` files.
  - **Add-ons keep their own data** (orders, jobs, loans) in their own tables, but every coin movement goes through the API.
- **Ledger tables:**
  ```
  accounts  (id, kind PLAYER|PLUGIN|EXTERNAL|MINT|TREASURY, plugin, acct_key, owner_player, name, currency, created_at)
  journal   (tx_id, ts, type, memo, source_plugin, actor, reference, reverses_tx)
  postings  (tx_id, account_id, amount BIGINT)   -- each tx sums to 0
  balances  (account_id, amount BIGINT, last_tx)
  ```
- **Testing:**
  - JUnit in each module, especially that core's books always balance and policy limits hold.
  - MockBukkit tests for both Vault setups (Vault only, and VaultUnlocked).
  - **`sim/`** drives core, Market, Bank and Trade with fake players at the 2, 20 and 200-player scales. It's used to tune defaults and to check that money supply and prices stay sane over months of simulated play.

## 15. Roadmap

| Phase | Ships | Usable on its own? |
|---|---|---|
| **0: CapitalEngine core and API** | Ledger, Mint and Treasury, `/money`, `/pay`, Vault and VaultUnlocked adapters, per-plugin policy, `/ce import`, `/ce audit`, `/ce reverse`, published API jar | **Yes**: replaces EssentialsX Eco and shows which plugins print money |
| **1: CapitalMarket** | Market with the server market maker, Jobs Board, autopilot (`MonetaryPolicy`), Market News, tutorial, `/economy` | Yes |
| **2: CapitalBank** | Savings, simple loans, land upkeep | Yes, with or without Market |
| **3: CapitalDashboard** | In-game and web dashboard, alerts | Yes |
| **4: CapitalTrade** | Ports, exports with quotas, opt-in imports, Port News | Yes, with or without Market |
| **5+: Future add-ons** | In the order owners ask for them | Each one |

`sim/` grows alongside: core plus Market scenarios in Phase 1, Bank scenarios in Phase 2, Trade scenarios in Phase 4.

## 16. Deliberately not doing
- **Changing vanilla drops or mechanics.** The economy works through prices and jobs only.
- **The server creating items from nothing.** The only exceptions are opt-in: an owner marking an item "always in stock" in Market, or listing CapitalTrade imports.
- **Real-money currency purchases.**
- **Running on top of another economy plugin,** or the main add-ons (Market, Bank, Dashboard, Trade) depending on each other.
- **Economics jargon** in player-facing text.

## 17. Open decisions (recommendation first)
1. **Paper-only?** *Yes.* It's simpler, and it's where most servers are.
2. **Should the server sell items it doesn't have ("always in stock")?** *No by default;* owners can enable it per item.
3. ~~Where is the API jar published?~~ **Decided:** the public Maven repo at `https://patelheet30.github.io/capitalengine-api/m2` (GitHub Pages on `patelheet30/capitalengine-api`), binary and Javadoc only.
4. **Currency name and symbol.** Default "coins"; owners can configure it.
5. ~~Licence~~ **Decided: GPL-3.0-only with the [CapitalEngine Add-on Exception](ADDON-EXCEPTION.txt).** Add-ons built only on the API may use any licence, including closed source. (Before going open source, the project was closed source and the API jar shipped under API licence 1.0.)

---

## Appendix A: Autopilot steering rule (for developers)

Once per Week, in CapitalMarket:
```
p         = % change in the price basket vs last week (population is handled by the per-active-player floor, not here)
target    = owner target converted to weekly (default ≈ 0.5%/week)
error     = p − target

rate      = clamp(rate + clamp(0.5·error, ±0.25pp), min, max)              # savings & loan rates
newMoney  = baseNewMoney × clamp(1 − error·4, 0.5, 1.5)                     # §6.3 total, then clamped by core caps
saveShare = clamp(0.33 + error·4, 0.1, 0.7)                                 # share of newMoney for savings interest
spendShare= 1 − saveShare                                                    # split between server jobs and server buyer
mmSpread  = clamp(0.30 + 0.4·playerVolumeShare, 0.30, 0.50)                 # market maker gap widens as players trade more
```

## Sources
- VaultUnlockedAPI: https://github.com/TheNewEconomy/VaultUnlockedAPI
- VaultUnlocked: https://github.com/TheNewEconomy/VaultUnlocked
- VaultUnlocked 2.20.0 notes: https://hangar.papermc.io/TNE/VaultUnlocked/versions/2.20.0
- Legacy VaultAPI: https://github.com/MilkBowl/VaultAPI
