# CLAUDE.md — CapitalEngine

Development guide for this repository. Read this first, then the `Plan.md` for whichever subproject you're touching.

## What this is
CapitalEngine is a **modular Minecraft economy for Paper servers**. It's simple for players, kid-friendly, and works like a real economy underneath:
- every coin has a recorded source (double-entry ledger, Mint, Treasury)
- players set prices
- an optional autopilot keeps inflation steady

The project is a **monorepo of separate plugins:**
- **One required core** (`CapitalEngine`): ledger, `/money`, `/pay`, Vault adapters, per-plugin limits, import and audit, and the **public API**.
- **Optional add-ons:** Market, Bank, Dashboard, Trade. Each is built only on that API.

The full design is in [Plan.md](Plan.md).

## Status
**Phase 0 (`api` + `core`) is built and tested** (`./gradlew build`); its real-server and Bedrock smoke tests are still open (see the "Done when" list in [core/Plan.md](core/Plan.md)). Market, Bank, Dashboard, Trade and `sim` have only their `Plan.md` files so far.

Build order (root [Plan.md §15](Plan.md)):
- **Phase 0:** `api` + `core`
- **Phase 1:** `market`
- **Phase 2:** `bank`
- **Phase 3:** `dashboard`
- **Phase 4:** `trade`

`sim` grows alongside each phase. Don't start an add-on before the API surface it needs exists in `api/`.

## Where everything lives
```
CapitalEngine/
├─ CLAUDE.md            ← this file (dev rules)
├─ Plan.md              ← ecosystem overview: problem, design rules, money flows, liquidity, roadmap
├─ api/      Plan.md    → capitalengine-api.jar   the contract (interfaces, records, events). No implementation.
├─ core/     Plan.md    → CapitalEngine.jar       ledger, Mint/Treasury, Vault + VaultUnlocked, policy, clock, activity, /money /pay /ce
├─ market/   Plan.md    → CapitalMarket.jar       market + server market maker, Jobs Board, autopilot (MonetaryPolicy), Market News, tutorial
├─ bank/     Plan.md    → CapitalBank.jar         savings, simple loans, land upkeep
├─ dashboard/Plan.md    → CapitalDashboard.jar    snapshots, alerts, /cdash, local web page, Prometheus
├─ trade/    Plan.md    → CapitalTrade.jar        Ports: exports with quotas, opt-in imports with tariffs, Port News
└─ sim/      Plan.md    → (test-only)             MockBukkit simulator: fake players, scenarios, CI gate
```

Once code exists, each subproject follows the standard Gradle layout:

| Path | Contents |
|---|---|
| `src/main/java/com/patelheet/capitalengine/<module>/` | Code |
| `src/main/resources/` | `plugin.yml`, `config.yml`, `messages.yml`, `db/<dialect>/V<n>__*.sql`, … |
| `src/test/java/...` | Tests |

Repo root: `settings.gradle.kts` and `build.gradle.kts` (shared conventions), plus `gradle/libs.versions.toml` (all dependency versions in one place).

**The plans are the spec.**
- If a subproject `Plan.md` disagrees with the root `Plan.md`, the **subproject plan wins**. Fix the root.
- When an implementation decision changes the design (a new API member, a renamed table, a different default), **update the relevant `Plan.md` in the same change.**

## Target platform
| | |
|---|---|
| Server | **Paper** (and forks like Purpur). Spigot isn't supported. Folia isn't yet, but code must use Paper's region- and entity-aware schedulers so it can be. |
| Minecraft versions | **1.21.11 → 26.2** (Mojang's year-based numbering starts at 26.1) |
| Java | Servers run Java 21 (1.21.11) or **Java 25 (26.1+)**. We compile with `options.release = 21` (Gradle toolchain 21 or newer), so **one jar runs on every supported version.** |
| Bedrock | Supported through **Geyser + Floodgate**. When a feature can't be made to work on Bedrock, **Java comes first.** Degrade gracefully; never break Java to suit Bedrock. |

### Version compatibility rules
- **Compile against the lowest supported API:** `io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT`. Never use an API that's newer than 1.21.11 unless it's behind a runtime check, with a fallback, in a small isolated class.
- **No NMS or CraftBukkit internals, and no `paperweight-userdev`.** Use API only.
- **No APIs marked `@ApiStatus.Experimental`** (for example, the Paper data-component API) in code that must work on 1.21.11. Use `ItemMeta` instead.
- **Avoid APIs deprecated for removal.** If one is unavoidable, isolate it.
- **`plugin.yml` uses `api-version: '1.21.11'`.** Use `plugin.yml`, not `paper-plugin.yml`, because we rely on `libraries:` and the classic `depend`/`softdepend` behaviour.
  - **To verify when setting up the build:** that 26.x servers accept this `api-version`.
- **Test matrix:**
  - MockBukkit (1.21 API) for unit and integration tests.
  - A **smoke test on real Paper servers** for 1.21.11, the latest 26.1.x and 26.2, using `run-paper` with a Java 25 launcher for 26.x. Check the exact 26.x `paper-api` coordinates on repo.papermc.io; the build-number scheme changed with 26.x.

### Bedrock (Geyser + Floodgate) rules
- **GUIs are plain chest inventories** (≤ 54 slots, item-icon buttons). Geyser translates these.
  - No anvil, sign or book text input.
  - Text and number entry uses a **chat prompt**, or a **Floodgate form** when Floodgate is installed.
- **Clickable chat doesn't work on Bedrock.** Every clickable prompt also prints a typed fallback ("or type `/pay confirm`"). Show raw URLs as text as well as links.
- **Never rely only on item lore or hover text** for important information. Put it in the item name or a chat line too.
- **Bedrock usernames have Floodgate's prefix** (default `.`, e.g. `.Steve`). Always resolve players by UUID; name lookups must accept the prefix.
- **Floodgate is always a soft dependency** (`compileOnly org.geysermc.floodgate:api`). The forms code lives in one class per plugin and is only loaded when Floodgate is present.

## Architecture rules (don't break these)
1. **Core owns all money.** Every coin movement goes through the CapitalEngine API, and add-ons never touch the ledger database.
2. **Add-ons never depend on each other.** Each declares `depend: [CapitalEngine]` only. They share things only through:
   - `MonetaryPolicy` and `IndicatorProvider` services, via Bukkit's `ServicesManager`
   - API events: `CapitalDayEvent`, `CapitalWeekEvent`, `CapitalTransactionEvent`, `CapitalAlertEvent`, …

   A plain `isPluginEnabled("X")` check is allowed *only* to change defaults (Market does this for Bank). The exception for the future is an add-on that is literally built on another one (Stocks on Business).
3. **Money is a `long` count of the smallest unit.** Never use `double` or `float` for money. The only conversions to and from `double`/`BigDecimal` happen in core's Vault adapters.
4. **Ledger rows are never updated or deleted.** Corrections are reversing transactions.
5. **Every transaction** has:
   - a `type` (`<module>.<action>`, e.g. `market.trade`, `bank.interest`)
   - a plain-language `memo` (players see it)
   - a `reference` (`<kind>:<id>…`, e.g. `fill:812`, `int:<day>:<uuid>`) whenever a retry or crash could repeat it
6. **Plugin accounts** are namespaced `<plugin>:<key>` (e.g. `capitalmarket:escrow`, `capitalbank:savings:<uuid>`). Only the owning plugin can take money out of them.
7. **Two-phase pattern for items plus coins:**
   1. Write a `PENDING` row in the add-on's own database.
   2. Send the money transaction with a `reference`.
   3. Finalise or roll back the row depending on the result.
   4. On startup, recover any `PENDING` rows with `findByReference`.
8. **Pure-Java logic packages** (order book, pricing, autopilot, loan maths, world prices, ledger rules) **must not import `org.bukkit`.** That keeps them unit-testable and lets `sim` drive them.
9. **Never block the main thread** on database work or on API futures. API futures complete on core threads, so switch back to the main or entity thread before touching Bukkit objects.
10. **The economic clock is core's Day and Week events.** Add-ons never run their own daily timers. Skip interest and similar payouts on `catchUp` events.
11. **Owner safety caps live in core** (`issue-per-day`, `treasury-per-day` per plugin). Add-on budgets work *inside* those caps.

## Code conventions
- **Package:** `com.patelheet.capitalengine.<module>`, where module is `api`, `core`, `market`, `bank`, `dashboard`, `trade` or `sim`. **Maven group:** `com.patelheet`.
- **Plugin names:** `CapitalEngine`, `CapitalMarket`, `CapitalBank`, `CapitalDashboard`, `CapitalTrade`.
- **Permission prefixes:** `capitalengine.`, `capitalmarket.`, `capitalbank.`, `capitaldash.`, `capitaltrade.`
- **PlaceholderAPI prefixes:** `capital_`, `capitalmarket_`, `capitalbank_`, `capitaltrade_`
- **Java 21 language level.** Records and sealed types are welcome. No Lombok and no Kotlin.
- **Messages use Adventure/MiniMessage,** and every player-facing string lives in `messages.yml`.
- **Config uses Bukkit YAML.** No config framework.
- **Storage:**
  - HikariCP plus plain JDBC. Schema migrations are numbered SQL files per dialect (`sqlite`, `mysql`, `postgres`).
  - There's no ORM and no migration framework.
  - Each add-on has its **own** database (SQLite file by default).
- **Dependencies:**
  - Runtime libraries load through `plugin.yml` `libraries:` (Paper's loader). Don't shade unless there's no alternative.
  - Integrations are `compileOnly` plus `softdepend`:
    - Vault and VaultUnlocked
    - PlaceholderAPI
    - Floodgate
    - GriefPrevention, Lands, WorldGuard
    - Citizens
  - **Ask before adding any new dependency.**
- **Keep it small.** No interfaces with a single implementation (except the API and genuine multi-implementation cases such as `ClaimSource`), no speculative config options, no frameworks. Mark deliberate shortcuts with a `// ponytail:` comment that names the limit and the upgrade path.
- **Player-facing text is for kids too.**
  - Plain words only: "Market fee", "Savings rate", "Prices this week". Never "CPI", "M1", "VWAP" or "Taylor rule".
  - Confirm before anything costs money.
  - Players can never go below zero.

## Build and run
```bash
./gradlew build                    # all modules
./gradlew :core:test               # one module's tests
./gradlew :core:runServer          # run-paper dev server (1.21.11) with the module + its deps
./gradlew :sim:test                # CI economy scenarios (< 2 min); arrives with the sim module
./gradlew :sim:run --args="scenarios/small-20.yml"   # long simulation / tuning sweeps
```
- Each plugin module builds its jar into `<module>/build/libs/`.
- `api` also publishes `com.patelheet:capitalengine-api` as a **binary jar plus Javadoc only** (no sources jar).

## Testing expectations
- **Money logic** (anything that moves coins, splits amounts or rounds) gets a unit test that fails if the logic breaks.
- **Core's `ApiContractTest`** must pass for any API or ledger change.
- **Vault adapters** are tested in both setups: legacy Vault only, and VaultUnlocked.
- **Economy-behaviour changes** (autopilot, pricing, budgets, defaults) must keep `./gradlew :sim:test` green. Re-run the relevant sweeps and update `sim/RESULTS.md` when defaults change.
- **Before calling a phase done,** run its "Done when" checklist in that subproject's `Plan.md`. That includes the real-server smoke test on 1.21.11 and on 26.x, and joining once as a Bedrock player through Geyser.

## Git, CI and releases
- **Two repos:** `patelheet30/CapitalEngine` (private, all source) and `patelheet30/capitalengine-api` (public, binaries and Javadoc only, served by GitHub Pages as a Maven repo at `https://patelheet30.github.io/capitalengine-api/m2`). Never push source to the public one; its workflows fail on `.java`/`.kt`/`.sql` files.
- **Work on a branch, open a PR, squash-merge once CI (`build`) is green.** Don't push to `main`. (GitHub's free plan can't enforce this on private repos, so it is a habit. The public repo is enforced.)
- **Versions** live in one place per module: `<module>/gradle.properties` (`moduleVersion=`). The API jar reports its own version to `CapitalApi.apiVersion()` from that.
- **Release a plugin:** bump `moduleVersion` in a PR, merge, then `git tag core-v1.0.1 && git push origin core-v1.0.1`. The workflow checks the tag is on `main` and matches the version, builds, and attaches the jar to a private GitHub Release.
- **Release the API:** same, with an `api-v<version>` tag. The workflow builds, tests, and pushes a `release/api-<version>` branch to the public repo, which opens a PR. Merging that PR publishes the Maven files and Javadoc and creates the public `v<version>` release. Run the "Release API" workflow manually with `dry_run` to rehearse.
- **No AI attribution on the public repo.** Commits, PR titles and bodies, release notes and files that go to `capitalengine-api` must not contain "Co-Authored-By: Claude", "Generated with Claude Code" or any similar line. (The attribution lines other tooling suggests are fine on the private repo only.)
- **Published versions are immutable.** Fix a bad release with a new patch version. Release tags can't be moved or deleted.

## Licence and confidentiality
- **Closed source, All Rights Reserved.** © Heet Patel (patelheet.com).
- **Don't add open-source licence files or headers.** Don't publish source anywhere, and don't push this repo to a public remote.
- **The API jar is the only thing distributed to third parties,** as binary plus Javadoc. Its terms are in [api/API-LICENSE.txt](api/API-LICENSE.txt), which the build copies into the jar as `META-INF/API-LICENSE.txt`. Any change to those terms needs a new licence version number.
- **Third-party libraries** keep their own licences. Respect them, and record any that are bundled.

## References
- Paper docs: https://docs.papermc.io
- Paper Maven repo: https://repo.papermc.io
- VaultUnlockedAPI: https://github.com/TheNewEconomy/VaultUnlockedAPI
- Legacy VaultAPI: https://github.com/MilkBowl/VaultAPI
- Geyser and Floodgate: https://geysermc.org/wiki
- MockBukkit: https://github.com/MockBukkit/MockBukkit
