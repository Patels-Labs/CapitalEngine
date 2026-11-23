# Backlog

Things we know about and have decided not to do yet. Newest first within each group. Remove an item when it ships.

## Market
- **Verify stall holograms on Bedrock.** The hologram is a text display entity; Geyser may not render it. The barrel and its right-click menu work either way. If it doesn't render, add a sign or an armor stand fallback for Bedrock-visible servers.
- **Citizens NPC stalls.** A stall can be any block today (`/market admin stall create`). An NPC stall calls the same `Stalls.canWithdraw` / `Menus.main`; it needs Citizens as a `compileOnly` + `softdepend` (already in `plugin.yml`), a click listener in its own isolated class, and a `kind` for the stall row.
- **Run `StoreDialectTest` against MySQL/MariaDB.** The SQL is written for all three databases and PostgreSQL passes, but no MySQL server was available when it was built. Point `CAPITAL_TEST_MYSQL` at a throwaway database named like `capitalengine_test` and run `./gradlew :market:test --tests '*StoreDialectTest'`.
- **Run the market's plugin-level tests against PostgreSQL/MySQL.** `MarketFlowTest` uses SQLite; only `Store` itself is tested on the other databases.
- **Same-IP trade filter** for the weekly price index (market/Plan.md §5.7). Needs a way to read a player's address without a new dependency.

## Decisions the simulator asked for (see sim/RESULTS.md)
- **Should CapitalMarket answer `budget("exports")`?** (trade/Plan.md §12, question 1.) Exports are the biggest source of new money once Trade is installed, and `exports.daily-budget` is a flat number: 1000 (the new default) pays about 90 coins a day per active player on 20 players but about 11 on 200. Folding exports into the autopilot's daily total would scale with the server and let "prices rising, create less" reach exports. It's a few lines in `MarketPolicyService`; re-run the §2 sweeps when it's built.
- **Should the autopilot set less aside for savings when savers hold little?** With CapitalBank installed, a third of the daily budget is reserved for savings interest (Appendix A), but interest runs at about 5 coins a day on 20 players, so the server buyer and jobs get about 20% less (923 against 1,164 coins a day on `small-20` against `no-bank`, 60 days). The unpaid share is never created, which restrains growth, but it also leaves players with fewer coins and more unmet needs. Capping the savings share at what the bank owed yesterday (it publishes `interest_paid_today` and could publish what was owed) would fix it.
- **The plan's 15% a month band can't be met on a new server.** Players undercut the server's sell price, so about three quarters of what the server pays is new money, and monthly growth is about `30 × daily new money ÷ supply`. It reaches 15% only once supply is about 200 days of new money (about 13,000 coins per active player at floor 100; starter coins are 250). Either the band applies only after supply reaches that level, or the floor should shrink as supply grows. The floor is now 100 (was 150): it steadies prices on Market alone, and costs 1 to 2 points of unmet needs with Bank.
- **Land upkeep is recycled, not a sink.** It goes to the Treasury, which the autopilot's budgets spend again. If owners want it to remove money, part of it could be destroyed (a new `upkeep.destroy-share`); not done, because nothing in the sim needs it yet.

## Simulator
- `big-200` (about 80 seconds) is not in CI; run it with `./gradlew :sim:run --args="big-200.yml"`. Bank and Trade aren't in `big-200`, `tiny-2` or `everyone-quits` yet.
- Bank's "coins arriving" collection, frozen land, dormant loans, Ports and Cargo, Port News and booms aren't checked by the sim (sim/RESULTS.md, "Not simulated yet").
