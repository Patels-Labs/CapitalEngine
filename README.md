# CapitalEngine

A modular economy for Minecraft Paper servers. It's simple for players and works like a real economy underneath: every coin has a recorded source, players set the prices, and an optional autopilot keeps prices steady.

Supports Paper (and forks such as Purpur) **1.21.11 to 26.2**, with Bedrock players through Geyser and Floodgate.

## Plugins

| Plugin | Folder | What it adds | Players use |
|---|---|---|---|
| **CapitalEngine** (required) | [`core/`](core) | Coin ledger, `/money`, `/pay`, Vault and VaultUnlocked support, per-plugin money limits, audits | `/money`, `/pay` |
| **CapitalMarket** | [`market/`](market) | Player market with a server market maker, Jobs Board, price autopilot, Market News | `/market`, `/jobs`, `/economy` |
| **CapitalBank** | [`bank/`](bank) | Savings with interest, simple loans, land upkeep | `/bank` |
| **CapitalDashboard** | [`dashboard/`](dashboard) | Owner dashboard in game and on a local web page, alerts, Prometheus metrics | `/cdash` (owners) |
| **CapitalTrade** | [`trade/`](trade) | Ports: exports with daily quotas, opt-in imports with tariffs | `/trade` |

Install CapitalEngine, then any add-ons you want. The four main add-ons (Market, Bank, Dashboard, Trade) never depend on each other. A future add-on may require the one it extends, such as Stocks on Business or Banks+ on Bank. Downloads are on the [Releases](../../releases) page; each plugin is released separately (`core-v…`, `market-v…`).

Player guides: [Market](market/docs/player-guide.md), [Bank](bank/docs/player-guide.md), [Trade](trade/docs/player-guide.md). Design: [Plan.md](Plan.md).

## Build your own add-on

Compile against the API and declare `depend: [CapitalEngine]`:

```kotlin
repositories { maven("https://patelheet30.github.io/capitalengine-api/m2") }
dependencies { compileOnly("com.patelheet:capitalengine-api:1.0.0") }
```

```java
CapitalApi api = Bukkit.getServicesManager().load(CapitalApi.class);
api.serverPay(this, api.accounts().player(uuid), 100, "myplugin.reward", "Daily reward");
```

The API contract is in [api/Plan.md](api/Plan.md). Add-ons that use CapitalEngine only through the API may use **any licence**, including closed source (see [Licence](#licence)).

## Building from source

```bash
./gradlew build                 # every plugin; jars land in <module>/build/libs/
./gradlew :core:runServer       # a Paper 1.21.11 test server with the plugin loaded
./gradlew :sim:test             # economy simulation checks
```

Needs JDK 21 or newer. See [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.

## Licence

CapitalEngine is free software under the [GNU GPL v3.0 only](LICENSE), with the [CapitalEngine Add-on Exception](ADDON-EXCEPTION.txt): plugins that use CapitalEngine only through its API can be released under any licence. Changes to CapitalEngine itself stay under the GPL.

Icons, the shared frame and the rules for naming your own add-on are in [branding/](branding/README.md).
