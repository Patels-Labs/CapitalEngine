# Backlog

Things we know about and have decided not to do yet. Newest first within each group. Remove an item when it ships.

## Market
- **Verify stall holograms on Bedrock.** The hologram is a text display entity; Geyser may not render it. The barrel and its right-click menu work either way. If it doesn't render, add a sign or an armor stand fallback for Bedrock-visible servers.
- **Citizens NPC stalls.** A stall can be any block today (`/market admin stall create`). An NPC stall calls the same `Stalls.canWithdraw` / `Menus.main`; it needs Citizens as a `compileOnly` + `softdepend` (already in `plugin.yml`), a click listener in its own isolated class, and a `kind` for the stall row.
- **Run `StoreDialectTest` against MySQL/MariaDB.** The SQL is written for all three databases and PostgreSQL passes, but no MySQL server was available when it was built. Point `CAPITAL_TEST_MYSQL` at a throwaway database named like `capitalengine_test` and run `./gradlew :market:test --tests '*StoreDialectTest'`.
- **Run the market's plugin-level tests against PostgreSQL/MySQL.** `MarketFlowTest` uses SQLite; only `Store` itself is tested on the other databases.
- **Same-IP trade filter** for the weekly price index (market/Plan.md §5.7). Needs a way to read a player's address without a new dependency.
- **Stash-write loss window.** Items leave the inventory a moment before the Stash write is queued (`Actions`, marked `ponytail:`). Fix by recording the withdrawal first, or by an inventory snapshot rollback.
- **Project-chest crash window.** A crash between a project delivery being paid and the items reaching the chest loses those items (`JobsBoard.toChest`).

## Decisions the simulator asked for (see sim/RESULTS.md)
- **"The town needs" jobs don't remove server stock** (market/Plan.md §5.8), so a flood of one item takes about four months to clear at the 2% daily use-up. Either raise `maker.stock-use-rate`, or make those jobs consume the surplus from server stock.
- **Budgets don't bind in a market-only economy.** Money creation is limited by what players sell, not by `floor-per-active-player` above about 150. Revisit once Bank and Trade add sinks and lenders.
- **Money grows about 13x in 90 days on a 20-player server** with the current defaults, against the plan's 15% a month. The floor (150 coins a day per active player) is the lever; 50 gives 8.6x and steadier prices.

## Simulator
- Bank roles (Saver, Borrower) and Trade exports/imports, when those phases ship.
- `big-200` has not been run in CI (it takes longer); run it with `./gradlew :sim:run --args="big-200.yml"`.
- Tune and record the defaults for Bank and Trade in `sim/RESULTS.md`.
