# Simulator results

What `./gradlew :sim:run` showed for the defaults of CapitalMarket, CapitalBank and CapitalTrade, and how far to trust it. Re-run the sweeps and update this file when a default changes (CLAUDE.md, "Testing expectations").

## How to read this
- Agents are deliberately simple (sim/Plan.md §5.3). Miners and farmers sell what they make, traders buy cheap listings, savers put half their spare coins in the bank every third day, borrowers take the largest loan they are offered (one loan in ten is not repaid on time: that borrower spends every coin as it comes in), and everyone eats, wears out a tool and spends 60% of spare coins on things other players make. Each player owns land: the role's figure in the scenario's `land:` section, give or take half. **Treat the numbers as relative, not absolute.** They compare settings; they don't predict a real server. Land area, the saver and borrower rules, and borrower reliability are made up, so they are swept too (`sim.land-scale`, `sim.borrower-reliability`).
- Columns: *money* is the final supply over the day-1 supply; *28 days* is growth over the last 28 days, which is what the plan's 15% a month band measures; *swing* is the average size of the weekly price change after week 4; *trade share* is player-to-player value; *unmet* is food and tool needs that could not be met. *interest*, *upkeep* and *exports* are coins over the whole run. *fails* counts runs outside the scenario's bands (always the unmet-needs or inflation band; no run broke the books, a bank check, a loan rule or an export limit).
- Unless a row says otherwise, runs are 90 days and every row is the average of seeds 1, 2 and 3.
- The three stacks: **market** is `no-bank` (CapitalMarket only); **+bank** is `small-20` (the same 20 players, with CapitalBank: savings, loans, land upkeep); **+bank+trade** is `small-20 --plugins market,bank,trade`.
- **Naming a setting.** Prefix it with its module and write the rest as it appears in that module's `config.yml`: `market.autopilot.floor-per-active-player`, `market.market.fee` (the fee is under `market:` in the market's file), `bank.upkeep.per-100-blocks`, `trade.exports.daily-budget`. Agent settings start with `sim.`. The simulator refuses a key the module's `config.yml` doesn't have, and warns when two values give exactly the same run (it did for `bank.savings.interest-cap` above 10,000: no saver gets that far). Several `--sweep`s run every combination and `--seeds 1,2,3` repeats each one: `./gradlew :sim:run --args="small-20.yml --days 90 --seeds 1,2,3 --sweep market.autopilot.floor-per-active-player=50,100,150 --sweep sim.land-scale=0.5,1,2"`.
- **With CapitalMarket installed, three bank settings do nothing:** `rates.savings-weekly`, `rates.loan-weekly` and `savings.savings-daily-budget`. The rates and the interest budget come from Market's autopilot (`market.autopilot.rate.*`), capped by the bank's `rates.max-*`.

## Defaults changed
| Setting | Was | Now | Why |
|---|---|---|---|
| `market.autopilot.floor-per-active-player` | 150 | **100** | Market alone: less growth, half the price swing, more trade between players, the same unmet needs. With Bank: less growth for about 2 points more unmet needs (§1). |
| `trade.exports.daily-budget` | 5000 | **1000** | Exports were the biggest source of new money. 1000 is the lowest budget at which exports still hold the Market price of a surplus item near its world price (§2). |

Nothing in CapitalBank changed: none of its settings moves money growth much (§3).

Every scenario at its own seed, 90 days, before and after:

| Scenario | Money | 28 days | Swing | Trade share | Unmet |
|---|---|---|---|---|---|
| tiny-2 | x22.7 → x16.9 | +57% → +39% | 5.1% → 5.2% | 60 → 71% | 0 → 0% |
| small-20 (+bank) | x12.6 → x10.3 | +31% → +32% | 3.4% → 2.9% | 61 → 65% | 16 → 22% |
| no-bank (market only) | x15.1 → x14.0 | +50% → +45% | 5.8% → 3.1% | 60 → 63% | 8 → 7% |
| with-trade (+bank+trade, farm flood) | x22.6 → x22.2 | +44% → +51% | 5.4% → 2.8% | 41 → 80% | 19 → 16% |
| farm-flood | x21.8 → x16.2 | +47% → +44% | 5.0% → 5.3% | 31 → 44% | 11 → 10% |
| everyone-quits | x11.0 → x8.1 | +8% → +8% | 6.0% → 5.8% | 60 → 70% | 1 → 5% |
| big-200 (market only) | x7.6 → x7.6 | +39% → +39% | 1.2% → 1.4% | 33 → 32% | 9 → 10% |

Single seeds are noisy. small-20 seed 42 is the worst case for unmet needs among the eight seeds run (§1). `small-20` and `no-bank` now have the same 20 players (5 miners, 5 farmers, 2 traders, 3 savers, 2 borrowers, a builder and 2 idlers), so these rows are not comparable with the old file's `small-20` (market only, 14 players plus 6 idlers, x14.1).

## Why money grows so fast, and how far the plan's band is
1. **The server buys and almost never sells.** Agents list what the server can't buy just under its sell price, and buyers take the cheaper player listing, so purchases go to players and the Treasury receives little more than the 2% fee (about 15 coins a day on `no-bank`, apart from a few days with a burst of server sales). Every coin the server's buyer and jobs pay is then new money, and server stock keeps rising (3,135 items on day 1, 10,008 on day 89). Real players also undercut the server, so this is probably not only an artefact of the agents.
2. **So growth is about linear:** roughly `floor × active players` coins a day while the budgets bind, and what players can sell when they don't. In percentage terms, monthly growth is about `30 × floor × active players ÷ money supply`. The plan's 15% a month is reached only once supply is about `200 × floor` per active player: 20,000 coins each at a floor of 100. Starter coins are 250. **The band can't be met in the first months of a server with any floor that keeps players fed.** What the floor changes is how fast that supply is reached. *x-fold* over 90 days mostly reflects the small starting supply. The last-28-days column is the fairer measure.
3. **The price index follows server stock, not money** (unchanged from before). Prices fall when stock builds up and rise when it runs out, so the autopilot's "prices rising, create less" feedback is weak.

## 1. The floor, on all three stacks
| Stack | Floor | Money | 28 days | Swing | Trade share | Unmet | Interest | Upkeep | Exports | Fails |
|---|---|---|---|---|---|---|---|---|---|---|
| market | 25 / 50 / 75 / **100** / 150 | x4.0 / x7.7 / x11.5 / x14.2 / x15.5 | +28 / +35 / +39 / +41 / +51% | 2.7 / 1.2 / 1.6 / 3.0 / 5.3% | 76 / 66 / 65 / 61 / 55% | 19 / 12 / 9 / 7 / 7% | | | | 1 / 0 / 0 / 0 / 0 of 3 |
| +bank | 25 / 50 / 75 / **100** / 150 | x1.9 / x4.6 / x7.5 / x10.4 / x12.5 | +24 / +35 / +41 / +31 / +30% | 4.9 / 1.5 / 2.0 / 3.1 / 3.5% | 71 / 63 / 57 / 60 / 58% | 34 / 30 / 24 / 19 / 18% | 621 / 510 / 438 / 440 / 661 | 6.7k / 10.8k / 12.2k / 13.9k / 13.9k | | 3 / 3 / 3 / 1 / 0 of 3 |
| +bank+trade (budget 5000) | 25 / 50 / 75 / **100** / 150 | x26.5 / x25.9 / x24.7 / x24.2 / x22.3 | +47 / +45 / +41 / +42 / +49% | 19.7 / 21.2 / 17.0 / 17.0 / 9.1% | 86 / 83 / 79 / 70 / 48% | 18 / 19 / 19 / 19 / 19% | | | 127k / 124k / 117k / 115k / 103k | 3 / 3 / 3 / 2 / 2 of 3 |

Seeds 4 to 8, floor 75 / **100** / 125 / 150:

| Stack | Money | 28 days | Swing | Trade share | Unmet | Fails |
|---|---|---|---|---|---|---|
| market | x11.6 / x13.6 / x14.5 / x15.4 | +40 / +42 / +48 / +55% | 1.7 / 2.9 / 4.9 / 5.1% | 66 / 63 / 59 / 56% | 8 / 8 / 7 / 7% | 0 / 0 / 0 / 0 of 5 |
| +bank | x7.4 / x10.6 / x11.8 / x12.8 | +34 / +40 / +36 / +37% | 2.0 / 2.3 / 2.8 / 2.9% | 57 / 56 / 57 / 55% | 22 / 19 / 18 / 17% | 5 / 1 / 0 / 0 of 5 |

- **Market only (owners may install Market alone):** at 100, growth is lower, the weekly swing roughly halves (5.3% to 3.0%, and 5.1% to 2.9% on the second set of seeds), players trade with each other more, and unmet needs stay within a point. Below 75, unmet needs climb fast (19% at 25).
- **With Bank:** at 100, growth is lower over the run but not over the last 28 days, and unmet needs rise by 1 to 2 points on average. The CI seed (42) is the worst case: 16% to 22% at 90 days, and 19% at the 60 days CI runs (the band is 20%). That is the cost of the change.
- **With Trade at the old 5000 budget,** exports outweigh the floor: growth hardly depends on it, and a lower floor only moves the swing (Trade drains server stock, whose level sets prices).

## 2. Trade
**Export budget** (`trade.exports.daily-budget`; +bank+trade):

| Floor | Budget | Money | 28 days | Swing | Trade share | Unmet | Exports | Fails |
|---|---|---|---|---|---|---|---|---|
| 100 | 250 / 500 / **1000** / 2000 / 5000 | x13.6 / x16.8 / x21.7 / x24.2 / x24.2 | +41 / +45 / +53 / +40 / +42% | 1.7 / 2.0 / 3.8 / 17.4 / 17.0% | 62 / 67 / 76 / 71 / 70% | 19 / 18 / 18 / 19 / 19% | 22k / 44k / 84k / 115k / 115k | 1 / 0 / 0 / 3 / 2 of 3 |
| 150 | 250 / 500 / 1000 / 2000 / 5000 | x15.9 / x18.8 / x22.2 / x22.6 / x22.3 | +42 / +51 / +54 / +52 / +49% | 2.3 / 2.9 / 4.5 / 7.9 / 9.1% | 60 / 64 / 69 / 46 / 48% | 17 / 17 / 16 / 18 / 19% | 22k / 44k / 81k / 97k / 103k | 0 / 0 / 0 / 2 / 2 of 3 |

**Does Trade still hold a floor under Market prices?** (trade/Plan.md §11; `with-trade`, 70 days with a farm flood on days 30 to 39, floor 100). The server's average buying price for coal from day 29, as a share of coal's world price, with Trade and, on the same seed, without it:

| Budget | Seed 1 | Seed 2 | Seed 3 | Seed 42 |
|---|---|---|---|---|
| without Trade | 0.38 | 0.43 | 0.41 | 0.39 |
| 250 | 0.55 | 0.46 | 0.45 | 0.51 |
| 500 | 0.62 | 0.57 | 0.49 | 0.55 |
| **1000** | 0.87 | 1.01 | 0.85 | 0.99 |
| 5000 | 4.75 | 4.02 | 1.27 | 1.27 |

Coal is the item miners make more of than anyone uses, so its server stock sits far above target and its price far below its world price without Trade; wheat barely moves in a flood now that town jobs use it up. At 1000 the floor holds on every seed. At 500 and below it doesn't. At 5000 exports empty the server's coal stock, and its price shoots past the world price (and the weekly swing goes to 15% to 17%). The same runs: money x10.8 / x13.1 / x16.3 / x18.2 for budgets 250 / 500 / 1000 / 5000 (x8.8 without Trade), unmet needs 16% to 19% throughout.

- **Why the budget and not the quotas.** Halving or quartering every `quota-per-active-player` (`sim.export-quota-scale`) cuts exports less per coin than the budget does (quotas ×0.25 still gave 67k of exports, x19.1, against 84k and x21.7 for budget 1000), because exports concentrate on the few surplus items. On `big-200` exports reach core's 5000-a-day cap for CapitalTrade with full or quartered quotas (money x13.5 and x13.1, against x7.6 without Trade). At budget 1000 it is x9.3. A flat budget pays more per active player on a small server than on a big one. Tying it to the autopilot's daily total is the open question below.
- **`trade.exports.slide-k`** 0.25 / 0.5 / 0.75 (budget 1000): x22.3 / x21.7 / x22.4. The budget binds first, so the slide hardly matters. Unchanged.
- **Imports** (`with-trade` lists iron at 8 coins, dearer than the Market, for needs the Market can't meet): x16.8 off, x16.2 on. Only 10 to 100 coins of imports happen in a run, 20% of them as tariffs to the Treasury. They are a real sink but a small one, because the Market rarely runs out. They stay off by default.

## 3. Bank (+bank, floor 100)
| Setting | Values | Money | 28 days | Swing | Trade share | Unmet | Interest | Upkeep | Fails |
|---|---|---|---|---|---|---|---|---|---|
| `bank.upkeep.per-100-blocks` × `sim.land-scale` | 1 × 0.5 / **1 × 1** / 1 × 2 | x10.3 / x10.4 / x8.3 | +38 / +31 / +34% | 1.7 / 3.1 / 3.0% | 62 / 60 / 39% | 18 / 19 / 22% | 463 / 440 / 184 | 1.7k / 13.9k / 49.3k | 0 / 1 / 3 of 3 |
| | 2 × 0.5 / 2 × 1 / 2 × 2 | x10.3 / x10.2 / x6.4 | +41 / +33 / +25% | 2.0 / 3.1 / 3.2% | 62 / 55 / 38% | 18 / 21 / 24% | 492 / 361 / 194 | 2.7k / 19.3k / 64.4k | 1 / 2 / 3 of 3 |
| `bank.upkeep.free-blocks` | 5000 / **10000** / 20000 | x9.1 / x10.4 / x10.3 | +32 / +31 / +41% | 3.2 / 3.1 / 2.0% | 52 / 60 / 62% | 21 / 19 / 18% | 316 / 440 / 492 | 35.3k / 13.9k / 2.7k | 2 / 1 / 1 of 3 |
| `bank.savings.interest-cap` | 1000 / **10000** / 100000 | x10.4 / x10.4 / x10.4 | +30 / +31 / +31% | 3.1% | 60% | 19% | 368 / 440 / 440 | 13.9k | 1 of 3 each |
| `bank.loans.start-limit` | 100 / **500** / 2000 | x10.3 / x10.4 / x9.6 | +31 / +31 / +26% | 3.0 / 3.1 / 2.5% | 61 / 60 / 52% | 18 / 19 / 20% | | | 0 / 1 / 2 of 3 |
| `market.autopilot.rate.start-weekly` | 0.01 / **0.02** / 0.04 | x9.9 / x10.4 / x10.6 | +31 / +31 / +36% | 3.0 / 3.1 / 3.0% | 57 / 60 / 59% | 20 / 19 / 21% | 169 / 440 / 1076 | | 1 of 3 each |
| `sim.borrower-reliability` | 0.5 / **0.9** / 1 | x9.6 / x10.4 / x10.6 | +30 / +31 / +35% | 2.5 / 3.1 / 3.0% | 57 / 60 / 59% | 21 / 19 / 19% | | | 2 / 1 / 1 of 3 |

- **Upkeep is mostly recycled, not destroyed.** It goes to the Treasury, and the autopilot's budgets are "new money plus last week's Treasury income", so the coins come straight back out as server purchases and job pay. Doubling everyone's land more than triples upkeep (13.9k to 49.3k coins) but only takes money from x10.4 to x8.3, and players with big land and little income go broke (unmet 22%, trade share 39%). Upkeep is a fair cost for big land, but not the money sink the plan hoped for. The defaults stay.
- **Interest is tiny:** about 5 coins a day across all savers, because savers hold a few thousand coins at 2% a week. The interest cap only matters below what savers hold (1000 cuts interest by a sixth). None of this moves money growth.
- **Installing Bank sets a third of the autopilot's budget aside for savings interest** (Appendix A, `saveShare` 0.33), and almost none of it is paid. On seed 42 at floor 150, the server buyer's and jobs' daily budgets average 1,366 coins with Bank against 1,960 without, while interest runs at 3 coins a day. The unpaid share is never created, which is why Bank alone lowers growth (x15.5 to x12.5 at floor 150 on seeds 1 to 3). It also lowers what players can sell, which is most of why unmet needs are higher with Bank (7% to 12% with no land, 18% with land).
- **Bigger loans hurt.** A start limit of 2000 leaves more borrowers unable to feed themselves while they repay. 500 stays.
- **Loans never trapped anyone.** In every run, every loan ended (paid, or written off after 28 days behind) within its term plus the write-off days, the bank check matched the ledger every day, and interest never exceeded each saver's capped savings at the day's rate or the day's budget.

## 4. Market maker and fee (market only, floor 100)
| Setting | Values | Money | 28 days | Swing | Trade share | Unmet |
|---|---|---|---|---|---|---|
| `market.maker.k` | 0.25 / **0.5** / 0.75 | x13.4 / x14.2 / x13.6 | +44 / +41 / +39% | 1.6 / 3.0 / 5.3% | 66 / 61 / 60% | 10 / 7 / 8% |
| `market.maker.target-stock-default` | 320 / **640** / 1280 | x12.9 / x14.2 / x13.0 | +45 / +41 / +43% | 3.9 / 3.0 / 2.0% | 60 / 61 / 63% | 8 / 7 / 10% |
| `market.maker.stock-use-rate` | 0 / **0.02** / 0.05 | x10.6 / x14.2 / x12.2 | +36 / +41 / +41% | 3.2 / 3.0 / 3.0% | 19 / 61 / 63% | 7 / 7 / 9% |
| `market.market.fee` | 0 / **0.02** / 0.05 | x14.3 / x14.2 / x13.9 | +41 / +41 / +42% | 3.1 / 3.0 / 3.3% | 64 / 61 / 60% | 8 / 7 / 8% |

No change. A lower `k` steadies prices but leaves more needs unmet. Without the stock use-up, players stop trading with each other (19%): the server's stock, and so its low prices, keep building up.

## Other findings
**A 20x farm flood clears within four weeks** (measured when the floor was 150; at 100 the CI seed gives a price ratio of 1.00 and at most 1728 wheat in stock). "The town needs" jobs are posted whenever server stock is above its target, and every item delivered to one also uses up one of the server's own extra items (market/Plan.md §5.8). `farm-flood` for 70 days, wheat's average ask in the week before the flood (days 23 to 29) against four weeks after it ends (days 64 to 70), and the most wheat the server held from day 30:

   | Seed | Before: price after / before | Before: most stock | Now: price after / before | Now: most stock |
   |---|---|---|---|---|
   | 5 (CI) | 46 / 77 (0.59) | 7817 | 180 / 195 (0.93) | 1309 |
   | 7 | 45 / 70 (0.64) | 9165 | 179 / 200 (0.895) | 1848 |
   | 11 | 46 / 82 (0.56) | 7852 | 182 / 175 (1.04) | 1498 |

   Before, wheat never recovered: the stock only fell by the 2% daily use-up, so the price kept sliding before, during and after the flood. Now the stock stays near the 640 target and the price near its base: the flood barely shows in the server's wheat price at all (it shows as full Stashes and cheap player listings), so the old `aFarmFloodMakesWheatCheaperAndTheBooksStillBalance` check was removed (its drop had mostly been the slide that started before the flood). The stock check is the one that tells old from new. The cost: money grows faster because wheat keeps its value (`farm-flood` 70 days x12.1 to x16.6, `small-20` 90 days x13.4 to x14.1), and weekly swing in `small-20` rose from 3.7% to 4.4%, at the top of the old seed-noise range. Posting jobs only above twice the target (the old rule) kept `small-20` at x13.3 and 3.7%, but the price sawed between the two and seed 5 was still at 0.71 after four weeks. `tiny-2` (60 days) also grows more and swings more, x11.3 and 3.6% to x13.9 and 5.5%: town jobs are open on most days now, so the jobs share of the budget is pooled into the buyer's less often and is paid out at the jobs' premium instead. Seed 7 lands just under the test's 0.9 line, so recovery is "about four weeks", not a guarantee; the CI test uses seed 5 and compares weekly averages, not single days (a single day swings by up to 20% as jobs fill).
   **Only farmers serve the town in the sim** (sim/Plan.md §5.3), so the coal and iron surplus (both end near 4x their target) is never delivered. With miners and idlers also delivering, `small-20` grows x20.2 with 3.2% swing (old code x19.1, 2.4%, so the agent change, not this one, moves money), and the flood recovers less on seed 5 (0.73) because coal and iron jobs share the jobs budget with wheat; seeds 7 and 11 give 1.12 and 0.90, and wheat stock stays under 2700 (old code 7800 to 11300). Revisit the agent model with Bank and Trade.
**No config we tried breaks the books.** Money creation is bound by production and by core's per-plugin caps, so the bands are proven on synthetic bad data (`theBandsCatchProblemsWhenTheyHappen`) as well as by the stock use-up regression below.

## What the CI gate guarantees today
`./gradlew :sim:test` takes about 20 to 50 seconds. Every day of every CI scenario: the books balance (all balances add to zero), no player or plugin account goes below zero, Market's escrow accounts equal what open orders and jobs need, and core's nightly audit is clean, with no CRITICAL alert. With Bank (`small-20`, and again with a 500-coin interest cap so the cap binds): the bank check matches the ledger, interest stays within each saver's capped savings at the day's rate and within the day's budget, and no loan outlives its term plus the write-off days. With Trade (`with-trade`): exports stay inside the daily budget, coal's Market price is held near its world price and well above the run without Trade, and imports destroy coins and pay tariffs. `farm-flood` checks wheat stock and recovery, and that total server stock stays under 4x target (8,885 items on day 70; **with `maker.stock-use-rate: 0` it reaches 12,396 and the test fails**). Two runs of `with-trade` (every plugin) must give the same CSV byte for byte. The inflation, growth and unmet-needs bands in the scenario files are a regression envelope around today's behaviour, not the plan's targets (3 points a week, 15% a month, 5%).

## Not simulated yet
- Bank: coins arriving in the wallet paying owed upkeep and arrears straight away (switched off in the sim because it runs on a wall-clock debounce on another thread; the daily collection still takes them), frozen land actually stopping anyone from building (agents don't build), dormant players' frozen loans (no scenario has borrowers going dormant), real claim plugins (the sim hands the bank each player's area directly).
- Trade: Ports, menus, Cargo collection at a Port, and Port News (agents call `TradeService` directly and unload Cargo into their Stash); booms are in the runs but nothing checks they are announced; Trade on a 2-player server (Trade §11, first item).
- Bank and Trade on `big-200`, `tiny-2` and `everyone-quits` (those scenarios are market only, apart from the `big-200` Trade check in §2).
- Floodgate and GUI paths in every add-on.
