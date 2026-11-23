# sim — Headless economy simulator — Plan

> **Artifact:** none shipped (a test-only Gradle module) · **Drives:** [core](../core/Plan.md), [market](../market/Plan.md), [bank](../bank/Plan.md), [trade](../trade/Plan.md)
> **Parent plan:** [../Plan.md](../Plan.md)

## 0. Build status
**Built:** `Simulation`, `Scenario`, `Agent` (all seven roles, in one class), `Asserts`, `Report`, `SimMain`, `RESULTS.md`, and the seven scenarios `tiny-2`, `small-20` (with CapitalBank), `big-200`, `farm-flood`, `everyone-quits`, `no-bank` and `with-trade` (with CapitalBank and CapitalTrade). `:sim:test` runs `tiny-2`, `small-20` (twice: once with a low interest cap so the cap binds), `farm-flood`, `with-trade` with and without Trade, a determinism check on `with-trade`, an empty server and a band self-check, in 20 to 50 seconds.
**Differences:**
- Agents also *spend* (a share of spare coins buys what other roles make, then uses it up), because without it nothing recycles through the Treasury. `Agent.Role` is an enum, not a class per role.
- Every agent owns land (the scenario's `land:` section per role, give or take half). **The sim runs the bank's day itself** (`BankService.onDay` with the agents' land and who was online), after the market's day has settled, instead of letting the bank's Day listener do it: two workers moving money at once would make a run unrepeatable, and MockBukkit has no claim plugin. The bank's "coins arriving" collection is switched off for the same reason (it debounces on the wall clock); the daily collection still takes arrears and upkeep.
- **Saver:** every third active day, half of the wallet above a 20-coin reserve goes into savings; a wallet below the reserve is topped up from savings. **Borrower:** takes the largest loan offered whenever it has none; with chance `1 − sim.borrower-reliability` (default 0.9) a loan isn't repaid on time, and that borrower spends every coin as it comes in until the loan ends.
- With Trade, every role exports from its Stash while the ships pay more than the server's Quick Sell, traders also buy player listings below what the ships pay and export them, and a need the Market can't meet is imported if the scenario lists the item (`trade-imports:`). Cargo goes straight back into the Stash.
- Settings are named by module (`market.`, `bank.`, `trade.`, `core.`, or `sim.` for the agents), a key that isn't in that module's `config.yml` is refused, and `SimMain` takes several `--sweep`s (every combination), `--set`, `--plugins` and `--seeds`.
- Market's expiries use the economic day so a run can cover months; market keeps its own 7-day active-player average from the Day events so a run repeats exactly.
**Known gap:** the inflation, growth and unmet-needs bands in the scenario files are a regression envelope around today's behaviour, not the targets in §5.6, which no default meets yet (`RESULTS.md` explains why the growth band can't be met on a new server).

## 1. Purpose and scope
The simulator answers **"does this economy stay sane?"** before real players find out the hard way. It:
- runs the **real plugins** (core, Market, Bank and optionally Trade) on a **MockBukkit** server, with fake players acting as simple agents
- covers months of economic days in seconds or minutes, at the **2, 20 and 200-player** sizes from the root plan (§7.6), plus stress scenarios
- outputs a CSV time series and a summary, and **asserts** that the books balance and that money and prices stay within set bands
- is used to **tune the default config values** for every add-on, and as a **regression gate in CI**

## 2. Not in scope
- **Being shipped to owners.** An owner-facing "preview my config" command is possible later (§12).
- **GUIs, chat or real networking.** Agents call each add-on's public service class directly.
- **Realistic player psychology.** Agents are deliberately simple rules with random noise.
- **CapitalDashboard.** It's read-only and irrelevant to the economy's behaviour. It can be added later to test its rules.

## 3. Depends on
- `testImplementation`:
  - `project(":core")`, `project(":market")`, `project(":bank")`, `project(":trade")`
  - `org.mockbukkit.mockbukkit:mockbukkit-v1.21` (MockBukkit 4.x)
  - JUnit 5
- **Hooks** that the other plans already commit to, all enabled only when the system property `capitalengine.sim=true` is set:
  - **core** `ClockControl.advanceDays(n)` and `recordActivity(uuid, minutes)` (core §5.7)
  - **market** `MarketService` plus `stash().testDeposit(...)` (market §5.9)
  - **bank** `BankService` (bank §5.6)
  - **trade** `TradeService` with its inventory-skipping test hook (trade §5.6)

## 4. Package layout
```
sim/
├─ src/test/java/com/patelheet/capitalengine/sim/
│  ├─ Simulation            boots MockBukkit, loads plugins, runs days, collects metrics
│  ├─ Scenario              YAML → scenario model (players by role, days, events, config overrides, seed)
│  ├─ Agent                 one class: online chance, daily activity minutes, needs (food/tools), RNG,
│  │                        and the roles Miner Farmer Trader Saver Borrower Builder Idler
│  ├─ Metrics               per-day row from api.stats(), indicators, and sim-side extras (Gini, avg balance)
│  ├─ Assertions            band checks → pass/fail with reasons
│  ├─ Report                daily.csv + summary.md
│  ├─ SimScenariosTest      JUnit: short scenarios for CI
│  └─ SimMain               long runs via ./gradlew :sim:run --args=<scenario.yml>
└─ src/test/resources/scenarios/
   ├─ tiny-2.yml  small-20.yml  big-200.yml
   ├─ farm-flood.yml  everyone-quits.yml  no-bank.yml  with-trade.yml
```

## 5. How it works

### 5.1 Booting
1. Set `capitalengine.sim=true` and start `MockBukkit.mock()`.
2. Load the plugins in order: `CapitalEngine`, then `CapitalMarket` and/or `CapitalBank` and/or `CapitalTrade`, as the scenario says.
3. Each plugin gets a fresh temp data folder (SQLite files), and the scenario's `config:` overrides are written into each plugin's `config.yml` before it's loaded.
4. Create the agents. Each one is a MockBukkit `PlayerMock`, so core's join and activity tracking behave normally. Agents join and disconnect according to their online chance each day.

### 5.2 One simulated day
```
for day in 1..N:
  apply scheduled scenario events for this day (e.g. farmers ×20 production, 80% of agents go dormant)
  for each agent (in seeded-random order):
     if online today: join, recordActivity(minutes), act(), disconnect
  ClockControl.advanceDays(1)        # fires CapitalDayEvent (+ CapitalWeekEvent on boundaries) → interest, upkeep,
                                     # loan collection, server jobs, stock use-up, world prices, autopilot (weekly)
  wait for the core persist queue to drain
  Metrics.capture(day)
```
- Every service call is `join()`ed inside the sim, which is fine off the real server. That keeps each run **fully repeatable** for a given seed.
- **Time:** about 1–5 ms per agent action. A 200-player, 120-day run takes about 1–3 minutes on a laptop; the CI scenarios take under 2 minutes in total.

### 5.3 Agents (simple rules with seeded noise)
**Every agent:**
- has needs: `food` per day, and a tool every few days
- satisfies them from its Stash first, then with **Quick Buy**
- holds coins in its wallet and acts only when online

| Role | Produces (into Stash via test hook) | Behaviour |
|---|---|---|
| **Miner** | Ores and ingots (iron common, diamond rare) | Quick Sells most output. With probability p, lists at server `ref × U(0.9, 1.1)` instead. |
| **Farmer** | Crops and food (high volume) | Quick Sells, and delivers to server "town needs" jobs when they pay better than the server's bid |
| **Trader** | Nothing | Buys when an ask is below the 4-week VWAP × 0.9, relists at VWAP. If Trade is on, exports when the world price beats the Market bid. |
| **Saver** | Some mixed output | Sells, then deposits 50% of its spare wallet into savings every third day; withdraws when the wallet runs low |
| **Borrower** | Some mixed output | Takes a loan up to its limit. Repays reliably with probability `sim.borrower-reliability` (scenario-set, 0.9); otherwise spends every coin until the loan ends. |
| **Builder** | Nothing | Buys materials for owner projects and delivers them to project jobs |
| **Idler** | A little | Online 1 day in 7. Becomes dormant in "quits" scenarios. |

Production rates are per active day and set in the scenario. Items are consumed (food eaten, tools broken), so there's **real demand**, not just supply.

### 5.4 Scenarios (YAML)
```yaml
name: small-20
seed: 42
days: 120
plugins: [market, bank]          # + trade
players: { miner: 5, farmer: 5, trader: 2, saver: 3, borrower: 2, builder: 1, idler: 2 }
online-chance: { default: 0.6, idler: 0.15 }
production: { miner: { iron_ingot: 48, coal: 64, diamond: 1 }, farmer: { wheat: 192, carrot: 128 } }
borrower: { reliability: 0.9 }
events:
  - { day: 40, type: production-multiplier, role: farmer, x: 20 }      # farm flood
  - { day: 80, type: go-dormant, share: 0.8 }                          # everyone quits
config:                                # merged into plugin configs
  market: { autopilot: { target-monthly-inflation: 0.02 } }
bands:                                 # assertions (§5.6); omitted ones use defaults
  weekly-inflation-abs-after-warmup: 0.03
```

Bundled scenarios:

| Scenario | What it checks |
|---|---|
| `tiny-2` | The market maker carries the market. Money growth is driven by the per-player floor and stays small. |
| `small-20` | A healthy mix with CapitalBank. The bank check passes every day, interest stays within its caps and budget, and no loan traps a player. |
| `big-200` | Player trade share > 60%, the server's gap widens to ≥ 0.4, and most savings interest is Treasury-funded. |
| `farm-flood` | "The town needs" jobs use up the surplus from server stock (it stays under 4x the target), and the wheat price is back within 10% (weekly average) 4 weeks after the flood ends. |
| `everyone-quits` | Active money supply drops, daily new money falls toward floor × active players, and nothing explodes. |
| `no-bank` | `small-20`'s players and seed without CapitalBank: Market's autopilot reallocates the savings share, and money growth stays within band. |
| `with-trade` | `small-20` plus CapitalTrade and a farm flood. Exports put a floor under Market prices (coal's server price stays near its world price; on the same seed without Trade it falls well below), imports are a net money sink, and exports stay inside their daily limit. |

### 5.5 Metrics (`daily.csv`, one row per day)
- **From core `stats()`:**
  - day
  - money supply, active money supply
  - Treasury
  - issued and destroyed
  - Treasury in and out
  - active players
- **From the `IndicatorProvider`s:**
  - price index, weekly inflation
  - savings and loan rates
  - `mmSpread`, player trade share
  - server stock value
  - total savings, loan book, overdue, written off
  - upkeep collected
  - exports, imports, trade balance
- **Sim-only:**
  - Gini coefficient of wallet plus savings
  - median and p90 balance
  - share of agents whose needs went unmet that day (a starvation check)
  - prices of the scenario's watched items

### 5.6 Assertions (`Assertions`, configurable bands per scenario)
**Every day, every scenario:**
- core's `Auditor` check passes, so the books balance
- no non-Mint account is below zero
- Market's and Bank's escrow and loan checks pass

**After the warm-up (4 weeks):**
- |weekly inflation − target| ≤ `weekly-inflation-abs-after-warmup` (0.03) for steady scenarios
- monthly money growth ≤ `max-monthly-growth` (0.15) and ≥ `min-monthly-growth` (−0.10), except in `everyone-quits`
- unmet needs < 5% of agent-days (the economy doesn't starve players)

**Scenario-specific** checks are listed in the §5.4 table.

The results go to `summary.md`: pass or fail per assertion, the first failing day, and key final numbers.

### 5.7 Using it to tune defaults
`./gradlew :sim:run --args="small-20.yml --days 90 --seeds 1,2,3 --sweep market.autopilot.floor-per-active-player=50,100,150 --sweep sim.land-scale=0.5,1,2"` runs the scenario once per combination and seed, writes one CSV per run and prints one summary line per run. Once defaults are chosen they're written back into the add-ons' shipped `config.yml`, and the CI scenarios lock them in.

## 6. Data
Temp folders per run: plugin SQLite files, deleted afterwards unless `--keep`.

Output goes to `sim/build/sim-out/<scenario>/<timestamp>/`: `daily.csv`, `summary.md` and the `scenario.yml` that was used.

## 7. Config
Scenario YAML only (§5.4). There's no global sim config.

## 8. Commands
- `./gradlew :sim:test` runs `tiny-2`, `small-20` (60 days), `farm-flood` (70 days) and `with-trade` (70 days, with and without Trade). It's a CI gate.
- `./gradlew :sim:run --args="<scenario.yml> [--days N] [--seed S] [--plugins market,bank,trade] [--set key=v]... [--sweep key=v1,v2]... [--seeds 1,2,3]"` does long runs and sweeps.

## 9. Threading and failure handling
- **Everything runs on the test thread,** which MockBukkit treats as the main thread. Core's persist writer is a real thread, and the sim waits for it to drain at the end of each day.
- **A plugin exception** fails the run immediately with the day and agent in the message.
- **An assertion failure** doesn't stop a `:sim:run`; it's reported in the summary. In `:sim:test` it fails the JUnit test.
- **Determinism:** one seeded `Random` per agent, plus the scenario seed for world prices. Agents act in a seeded shuffled order, and there are no wall-clock reads. Core's clock is driven only by `ClockControl`.

## 10. Tests
The simulator *is* a test, and it also checks itself:
- the same seed run twice gives an identical `daily.csv` (byte-for-byte)
- a scenario with zero agents runs and passes trivially
- a deliberately broken config (for example `stock-use-rate: 0`, `floor-per-active-player: 100000`) makes the expected assertions **fail**, which proves they can catch problems

## 11. Done when
- [x] All seven bundled scenarios run, and the CI scenarios pass in under 2 minutes.
- [x] Repeated runs with the same seed are identical.
- [x] The default config values in market, bank and trade are justified by sweep results, recorded in `sim/RESULTS.md`.
- [x] A regression, such as removing the stock use-up, fails `:sim:test`.

## 12. Open questions
1. **Should a version be shipped to owners** as `/ce simulate <scenario>` or a CLI jar, so they can preview their own config? *Later.* It would need a non-MockBukkit runner or a bundled MockBukkit.
2. **Should we model player-shop plugins** (ChestShop-style Vault flows) as an agent type, to test core's `vault-mode` behaviour at scale? *Yes, once core is done:* a small "VaultShop" mock plugin.
