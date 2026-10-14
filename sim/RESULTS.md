# Simulator results

What `./gradlew :sim:run` showed for the market defaults, and how far to trust it. Re-run the sweeps and update this file when a default changes (CLAUDE.md, "Testing expectations").

## How to read this
- Agents are deliberately simple (sim/Plan.md §5.3): miners and farmers sell what they make, traders buy cheap listings, everyone eats, wears out a tool, and spends 60% of spare coins on things other players make. **Treat the numbers as relative, not absolute.** They compare settings; they do not predict a real server.
- Seed noise is small: five seeds of `small-20` for 90 days give money x13.6 to x14.1, weekly price swing 3.2% to 4.4%.
- Columns: *money* is the final supply over the day-1 supply; *swing* is the average size of the weekly price change after week 4; *trade share* is player-to-player value; *unmet* is food and tool needs that could not be met.

## Sweeps (small-20, 90 days, seed 42)
| Setting | Value | Money | Swing | Trade share | Unmet |
|---|---|---|---|---|---|
| `maker.k` | 0.25 / **0.5** / 0.75 | x15.9 / x13.5 / x11.2 | 2.8% / 3.1% / 3.9% | 50 / 49 / 38% | 8 / 8 / 7% |
| `maker.target-stock-default` | 320 / **640** / 1280 / 2560 | x11.1 / x13.5 / x16.4 / x17.1 | 3.8% / 3.1% / 2.5% / 3.6% | 41 / 49 / 55 / 65% | 10 / 8 / 7 / 4% |
| `maker.stock-use-rate` | 0.01 / **0.02** / 0.05 / 0.1 | x10.7 / x13.5 / x17.3 / x12.4 | 3.6% / 3.1% / 5.1% / 4.1% | 36 / 49 / 64 / 59% | 9 / 8 / 4 / 10% |
| `autopilot.floor-per-active-player` | 50 / **150** / 300 | x8.6 / x13.5 / x13.7 | 1.5% / 3.1% / 5.0% | 71 / 49 / 30% | 12 / 8 / 10% |
| `market.fee` | 0 / **0.02** / 0.05 | x13.5 / x13.5 / x12.8 | 2.9% / 3.1% / 3.4% | 50 / 49 / 47% | 8 / 8 / 9% |

Bold is the shipped default. No default was changed on the strength of this table.

## What the runs say
1. **The floor is the only lever that moves money growth.** Above about 150 coins a day per active player the budget stops binding: players cannot sell more than they produce. At 50 the economy grows less, prices are steadier and players trade with each other more, at the cost of more unmet needs.
2. **Money grows about 13x in 90 days on 20 players** (about 100 coins a day per active player stays in wallets, Gini 0.75). The plan's band is 15% a month. The only sinks in a market-only world are the server's price gap and the 2% fee; Bank upkeep and Trade imports are meant to be the rest. This is the main thing to revisit when those phases land.
3. **The price index measures server stock, not money.** Prices fall when stock builds up and rise when it runs out, so the autopilot's "prices rising, print less" feedback is weak: weekly swings of 3% to 10% come from items running out of stock, not from too much money.
4. **A 20x farm flood takes about four months to clear**, not four weeks. The 2% daily use-up is the only thing that removes the surplus, and "the town needs" jobs destroy player items without touching server stock (market/Plan.md §5.8). `aFarmFloodRecoversWithinFourWeeksOfEnding` is `@Disabled` with this reason.
5. **No config we tried breaks the economy.** Money creation is bound by production, and core's per-plugin caps (`CapitalMarket: issue-per-day 20000`) sit above that. So the bands are proven on synthetic bad data (`theBandsCatchProblemsWhenTheyHappen`) rather than by a broken config.

## What the CI gate guarantees today
Every day of every CI scenario: the books balance (all balances add to zero), no player or plugin account goes below zero, both escrow accounts equal what open orders and jobs need, and core's nightly audit is clean. The inflation, growth and unmet-needs bands in the scenario files are a regression envelope around the behaviour above, not the plan's targets (3 points a week, 15% a month, 5%).

## Not simulated yet
Bank roles, Trade exports and imports, and Floodgate/GUI paths (agents call `MarketService` directly).
