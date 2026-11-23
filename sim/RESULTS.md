# Simulator results

What `./gradlew :sim:run` showed for the market defaults, and how far to trust it. Re-run the sweeps and update this file when a default changes (CLAUDE.md, "Testing expectations").

## How to read this
- Agents are deliberately simple (sim/Plan.md §5.3): miners and farmers sell what they make, traders buy cheap listings, everyone eats, wears out a tool, and spends 60% of spare coins on things other players make. **Treat the numbers as relative, not absolute.** They compare settings; they do not predict a real server.
- Seed noise is small: five seeds (1 to 5) of `small-20` for 90 days give money x13.3 to x14.7, weekly price swing 4.3% to 5.4%.
- Columns: *money* is the final supply over the day-1 supply; *swing* is the average size of the weekly price change after week 4; *trade share* is player-to-player value; *unmet* is food and tool needs that could not be met.
- To sweep a market setting, name it as it appears in the market's `config.yml` after a `market.` prefix, which `SimMain` strips: `--sweep maker.k=0.25,0.5` or `--sweep market.market.fee=0,0.02`. (`--sweep market.fee=...` sets a key the plugin never reads, so every value gives the same run.)

## Sweeps (small-20, 90 days, seed 42)
| Setting | Value | Money | Swing | Trade share | Unmet |
|---|---|---|---|---|---|
| `maker.k` | 0.25 / **0.5** / 0.75 | x15.2 / x14.1 / x13.3 | 2.8% / 4.4% / 5.8% | 59 / 59 / 56% | 8 / 9 / 8% |
| `maker.target-stock-default` | 320 / **640** / 1280 / 2560 | x12.5 / x14.1 / x15.6 / x12.7 | 5.2% / 4.4% / 3.1% / 3.9% | 57 / 59 / 64 / 64% | 7 / 9 / 8 / 11% |
| `maker.stock-use-rate` | 0.01 / **0.02** / 0.05 / 0.1 | x12.2 / x14.1 / x13.0 / x13.9 | 4.3% / 4.4% / 4.1% / 4.8% | 38 / 59 / 56 / 61% | 8 / 9 / 9 / 8% |
| `autopilot.floor-per-active-player` | 50 / **150** / 300 | x6.3 / x14.1 / x15.0 | 1.2% / 4.4% / 6.6% | 70 / 59 / 39% | 14 / 9 / 10% |
| `market.fee` | 0 / **0.02** / 0.05 | x14.5 / x14.1 / x14.2 | 4.4% / 4.4% / 4.8% | 62 / 59 / 58% | 7 / 9 / 9% |

Bold is the shipped default. No default was changed on the strength of this table. These runs include "the town needs" jobs using up server stock (market/Plan.md §5.8); before that change the defaults gave x13.4, 3.7%, 43%, 9% on the same seed and code.

## What the runs say
1. **The floor is the only lever that moves money growth.** Above about 150 coins a day per active player the budget stops binding: players cannot sell more than they produce. At 50 the economy grows much less, prices are steadier and players trade with each other more, at the cost of more unmet needs.
2. **Money grows about 14x in 90 days on 20 players.** The plan's band is 15% a month. The only sinks in a market-only world are the server's price gap and the 2% fee; Bank upkeep and Trade imports are meant to be the rest. This is the main thing to revisit when those phases land.
3. **The price index measures server stock, not money.** Prices fall when stock builds up and rise when it runs out, so the autopilot's "prices rising, print less" feedback is weak: weekly swings of 3% to 10% come from items running out of stock, not from too much money.
4. **A 20x farm flood now clears within four weeks.** "The town needs" jobs are posted whenever server stock is above its target, and every item delivered to one also uses up one of the server's own extra items (market/Plan.md §5.8). `farm-flood` for 70 days, wheat's average ask in the week before the flood (days 23 to 29) against four weeks after it ends (days 64 to 70), and the most wheat the server held from day 30:

   | Seed | Before: price after / before | Before: most stock | Now: price after / before | Now: most stock |
   |---|---|---|---|---|
   | 5 (CI) | 46 / 77 (0.59) | 7817 | 180 / 195 (0.93) | 1309 |
   | 7 | 45 / 70 (0.64) | 9165 | 179 / 200 (0.895) | 1848 |
   | 11 | 46 / 82 (0.56) | 7852 | 182 / 175 (1.04) | 1498 |

   Before, wheat never recovered: the stock only fell by the 2% daily use-up, so the price kept sliding before, during and after the flood. Now the stock stays near the 640 target and the price near its base: the flood barely shows in the server's wheat price at all (it shows as full Stashes and cheap player listings), so the old `aFarmFloodMakesWheatCheaperAndTheBooksStillBalance` check was removed (its drop had mostly been the slide that started before the flood). The stock check is the one that tells old from new. The cost: money grows faster because wheat keeps its value (`farm-flood` 70 days x12.1 to x16.6, `small-20` 90 days x13.4 to x14.1), and weekly swing in `small-20` rose from 3.7% to 4.4%, at the top of the old seed-noise range. Posting jobs only above twice the target (the old rule) kept `small-20` at x13.3 and 3.7%, but the price sawed between the two and seed 5 was still at 0.71 after four weeks. `tiny-2` (60 days) also grows more and swings more, x11.3 and 3.6% to x13.9 and 5.5%: town jobs are open on most days now, so the jobs share of the budget is pooled into the buyer's less often and is paid out at the jobs' premium instead. Seed 7 lands just under the test's 0.9 line, so recovery is "about four weeks", not a guarantee; the CI test uses seed 5 and compares weekly averages, not single days (a single day swings by up to 20% as jobs fill).
   **Only farmers serve the town in the sim** (sim/Plan.md §5.3), so the coal and iron surplus (both end near 4x their target) is never delivered. With miners and idlers also delivering, `small-20` grows x20.2 with 3.2% swing (old code x19.1, 2.4%, so the agent change, not this one, moves money), and the flood recovers less on seed 5 (0.73) because coal and iron jobs share the jobs budget with wheat; seeds 7 and 11 give 1.12 and 0.90, and wheat stock stays under 2700 (old code 7800 to 11300). Revisit the agent model with Bank and Trade.
5. **No config we tried breaks the economy.** Money creation is bound by production, and core's per-plugin caps (`CapitalMarket: issue-per-day 20000`) sit above that. So the bands are proven on synthetic bad data (`theBandsCatchProblemsWhenTheyHappen`) rather than by a broken config.

## What the CI gate guarantees today
Every day of every CI scenario: the books balance (all balances add to zero), no player or plugin account goes below zero, both escrow accounts equal what open orders and jobs need, and core's nightly audit is clean. `aFarmFloodRecoversWithinFourWeeksOfEnding` also checks that the server's wheat stock stays under 4x its target after the flood starts and that wheat's weekly price is back within 10% four weeks after it ends. The inflation, growth and unmet-needs bands in the scenario files are a regression envelope around the behaviour above, not the plan's targets (3 points a week, 15% a month, 5%).

## Not simulated yet
Bank roles, Trade exports and imports, and Floodgate/GUI paths (agents call `MarketService` directly).
