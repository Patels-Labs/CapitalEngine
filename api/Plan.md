# capitalengine-api — Plan

> **Artifact:** `capitalengine-api.jar` · **Consumers:** CapitalEngine core (implements it), every add-on, and third-party plugins (all compile against it)
> **Parent plan:** [../Plan.md](../Plan.md) · **Implemented by:** [../core/Plan.md](../core/Plan.md)

## 1. Purpose and scope
This is the **contract** between CapitalEngine core and everything built on top of it. It is a small set of interfaces, records and events, and contains no implementation. Core implements it, and add-ons and third-party plugins use it.

The API lets a plugin:
- find and create accounts
- move money between accounts, atomically, with each movement attributed to the calling plugin
- pay from the server (Treasury first, then new money), collect into the Treasury, create money and destroy money, all within the owner's per-plugin limits
- read balances, history and economy-wide stats instantly
- listen to the shared economic clock (Day and Week) and to transaction events
- publish or read shared economic settings (`MonetaryPolicy`) and dashboard numbers (`IndicatorProvider`) **without add-ons depending on each other**
- raise alerts for owners

## 2. Not in scope
- **Items, GUIs and economics.** Prices and rates belong to add-ons. The API only carries them through the service interfaces.
- **Multiple currencies.** The database schema reserves room for them, but the API is single-currency until the Nations add-on exists.
- **Shared-account membership** (roles, members). It will be added in a later 1.x release for CapitalBusiness.
- **Implementation of any kind.** This jar contains no static `CapitalEngine.api()` helper, no defaults and no utilities beyond records.

## 3. Depends on
- `io.papermc.paper:paper-api` (`compileOnly`). That's all.
- Java 21 bytecode (`--release 21`), so one jar runs on Paper 1.21.11 (Java 21) through 26.2 (Java 25).

## 4. Package layout
```
com.patelheet.capitalengine.api
├─ CapitalApi              entry point (obtained from Bukkit's ServicesManager)
├─ Accounts                lookups / creation
├─ Account                 one account (read-only view)
├─ AccountKind             PLAYER | PLUGIN | EXTERNAL | TREASURY | MINT
├─ Currency                name, symbol, decimals, format/parse
├─ TransactionBuilder      multi-leg atomic transactions
├─ TxResult, Failure       outcome of a mutation
├─ Severity                INFO | WARN | CRITICAL (alerts)
├─ TxRecord, Posting       a committed transaction (history / events)
├─ Stats, PluginFlow       economy-wide numbers
├─ PolicyStatus            caller's remaining limits today
├─ service/
│  ├─ MonetaryPolicy       rates + daily budgets (implemented by an add-on, e.g. CapitalMarket)
│  ├─ IndicatorProvider    named numbers for dashboards (implemented by any add-on)
│  └─ Indicator, Unit
└─ event/
   ├─ CapitalTransactionEvent   (async, after commit)
   ├─ CapitalDayEvent           (economic day boundary)
   ├─ CapitalWeekEvent          (economic week boundary)
   ├─ CapitalAlertEvent         (anyone can raise; dashboards listen)
   ├─ CapitalAuditEvent         (nightly ledger self-check result)
   └─ CapitalPolicyLimitEvent   (a plugin hit/was denied by its owner-set limit; has the nested enum LimitKind)
```
Packages live under `com.patelheet.capitalengine` (the owner controls patelheet.com).

## 5. How it works

### 5.1 Getting the API
```java
// plugin.yml: depend: [CapitalEngine]
CapitalApi api = Bukkit.getServicesManager().load(CapitalApi.class);
if (!api.isCompatible("1.0.0")) { getLogger().severe("Needs CapitalEngine API 1.0+"); disable(); }
```

### 5.2 Money representation
- All amounts are **`long` counts of the smallest unit**. With 2 decimals, `12345` means 123.45 coins.
- Amounts must be greater than 0. Overflow is rejected with `INVALID_AMOUNT`.
- `api.currency().format(12345)` gives `"123.45 coins"`, using the owner's configured format. `parse("1.5k")` gives `150000`.

### 5.3 Accounts
```java
interface Accounts {
  Account player(UUID playerId);                          // created on first use
  Account plugin(Plugin owner, String key);               // namespaced "<plugin>:<key>", created on first use
  Optional<Account> find(String namespacedKey);           // e.g. "capitalbank:savings:<uuid>"
  Optional<Account> byId(UUID accountId);
}
interface Account {
  UUID id(); AccountKind kind(); String name();
  long balance();                                          // in-memory, instant, thread-safe
  Optional<UUID> ownerPlayer();                            // PLAYER accounts
  Optional<String> ownerPlugin();                          // PLUGIN / EXTERNAL accounts
}
```

| Kind | What | Can be a *source* of money by… |
|---|---|---|
| `PLAYER` | One per player | Any plugin (same trust as Vault) |
| `PLUGIN` | An add-on's own accounts (escrow, savings, …) | **Only the owning plugin.** Other plugins can pay *into* it but never take *out*. |
| `EXTERNAL` | Non-player accounts created by Vault plugins (e.g. Towny towns) | Any plugin, through the Vault adapters |
| `TREASURY` | The server's wallet (`api.treasury()`) | **Only `serverPay`**, which applies the caller's Treasury limit. Any transaction may pay *into* it. |
| `MINT` | Creates and destroys money | Never directly. Only `issue`, `destroy` and `serverPay`. It isn't exposed as an `Account`. |

No account except `MINT` can ever go below zero.

### 5.4 Moving money
Every mutating call:
- takes the calling `Plugin` for attribution and policy
- takes a `type` (dotted, e.g. `market.trade`) and a `memo` (up to 128 characters, shown to players and owners)
- returns `CompletableFuture<TxResult>`

```java
interface CapitalApi {
  String apiVersion();  boolean isCompatible(String minVersion);
  Accounts accounts();  Account treasury();  Currency currency();

  CompletableFuture<TxResult> transfer (Plugin caller, Account from, Account to, long amount, String type, String memo);
  CompletableFuture<TxResult> serverPay(Plugin caller, Account to,   long amount, String type, String memo);
  CompletableFuture<TxResult> collect  (Plugin caller, Account from, long amount, String type, String memo); // from → Treasury
  CompletableFuture<TxResult> issue    (Plugin caller, Account to,   long amount, String type, String memo); // Mint → to
  CompletableFuture<TxResult> destroy  (Plugin caller, Account from, long amount, String type, String memo); // from → Mint
  TransactionBuilder transaction(Plugin caller, String type, String memo);

  CompletableFuture<Optional<TxRecord>> findByReference(Plugin caller, String reference);
  CompletableFuture<List<TxRecord>>     history(Account account, int limit, long beforeTxId);  // newest first

  Stats stats();                         // instant snapshot
  PolicyStatus policy(Plugin caller);    // caller's remaining limits today
  Optional<MonetaryPolicy> monetaryPolicy();   // convenience lookup of the service
  void alert(Plugin source, Severity severity, String code, String message);  // fires CapitalAlertEvent safely from any thread
}

interface TransactionBuilder {
  TransactionBuilder move(Account from, Account to, long amount);
  TransactionBuilder issue(Account to, long amount);
  TransactionBuilder destroy(Account from, long amount);
  TransactionBuilder serverPay(Account to, long amount);  // Treasury-first split decided at commit (rules below)
  TransactionBuilder reference(String reference);     // idempotency key, unique per plugin
  TransactionBuilder actor(UUID playerId);            // who caused it (for audits), optional
  CompletableFuture<TxResult> commit();
}
```

**`serverPay`, step by step.** All of it is one atomic transaction:
1. `fromTreasury = min(amount, treasury balance, caller's remaining Treasury limit today)`
2. `issued = amount − fromTreasury`
3. If `issued` is more than the caller's remaining issue limit today, **nothing happens** and the result is `POLICY_LIMIT`.
4. Otherwise: Treasury −fromTreasury, Mint −issued, recipient +amount. `TxResult` reports both parts.

**The rules:**
- **All or nothing.** A multi-leg transaction either applies completely or not at all. Legs are checked against the *net* effect on each account.
- **Policy is per leg.** `issue` legs count against the issue limit. Moves out of the Treasury aren't allowed in a builder (use a `serverPay` leg). Moves into the Treasury are fine.
- **Shortcuts.** The single-call methods (`serverPay(...)`, `issue(...)`, …) are shortcuts for a one-leg builder with no reference. Use the builder whenever you need a `reference`.
- **Idempotency.** Commits carrying a `reference` that was already used by this plugin fail with `DUPLICATE_REFERENCE` and return the existing `txId`. Callers treat that as success. This is how add-ons safely retry and recover after a crash.
- **Ordering.** Transactions apply in the order core receives them.

**When things happen:**
- **Balance changes are visible immediately.** Once a mutating call returns, every reader (`balance()`, `stats()`, Vault) already sees the new balances, even before the future completes.
- **The future completes once the transaction is safely in the database.**
- **The future completes on a core thread, not the main thread.** Add-ons must switch back to the main or region thread before touching Bukkit objects.

```java
record TxResult(boolean success, long txId, Failure failure, String message, long fromTreasury, long issued) {}
enum Failure { NONE, INSUFFICIENT_FUNDS, POLICY_LIMIT, DENIED, INVALID_AMOUNT, NO_ACCOUNT,
               NOT_ACCOUNT_OWNER, DUPLICATE_REFERENCE, ECONOMY_PAUSED, INTERNAL }
record Posting(UUID accountId, long amount) {}               // signed; postings of a tx sum to 0
record TxRecord(long txId, Instant time, String type, String memo, String sourcePlugin,
                @Nullable UUID actor, @Nullable String reference, @Nullable Long reversesTxId, List<Posting> postings) {}
```

`ECONOMY_PAUSED` means core can't reach its database right now (core §9). Add-ons should show "The bank is having a nap, try again in a minute!"-style messages.

### 5.5 Stats (instant, from memory)
```java
record Stats(
  long moneySupply,            // −Mint: every coin that exists
  long activeMoneySupply,      // excludes players inactive for ≥ dormant-after-days
  long treasuryBalance,
  long issuedToday, long destroyedToday,
  long treasuryInToday, long treasuryOutToday,
  Map<String, PluginFlow> flowsToday,   // by source plugin
  int activePlayersToday,      // ≥ 5 active (non-AFK) minutes today
  long activeMinutesToday,
  double avgActivePlayers7d,
  long dayIndex, long weekIndex) {}
record PluginFlow(long issued, long destroyed, long treasuryIn, long treasuryOut, long transferred) {}
record PolicyStatus(long issueRemainingToday, long treasuryRemainingToday, boolean denied) {}
```

### 5.6 Shared services (implemented by add-ons)
Add-ons register these with Bukkit's `ServicesManager` like any other service. **Core implements neither.**

```java
interface MonetaryPolicy {
  double savingsRatePerDay();          // e.g. 0.0028 ≈ 2%/week
  double loanRatePerDay();
  OptionalLong budget(String channel); // today's max serverPay spend for a channel; empty = "no opinion, use your config"
}
// Known channels (by convention): "savings", "jobs", "buyer". Others (e.g. "exports") may return empty.

interface IndicatorProvider {
  String id();                         // e.g. "capitalmarket"
  String displayName();
  List<Indicator> indicators();        // must be cheap and thread-safe (return cached values)
}
record Indicator(String key, String label, double value, Unit unit) {}
enum Unit { COINS, PERCENT, COUNT, INDEX, RATE_PER_DAY }
```
- **Only one `MonetaryPolicy` is used:** whichever has the highest priority. Many `IndicatorProvider`s can coexist; Dashboard reads `getRegistrations(IndicatorProvider.class)`.
- **Consumers must work without either service.** For example, CapitalBank falls back to the rates in its own config when no `MonetaryPolicy` is registered.

### 5.7 Events

| Event | Thread | When | Payload |
|---|---|---|---|
| `CapitalTransactionEvent` | async | After each transaction is safely in the database | `TxRecord` |
| `CapitalDayEvent` | main/global | Economic day boundary | `dayIndex`, `LocalDate`, `catchUp`, `endedDay` (final `Stats` of the day that just ended, captured before counters reset) |
| `CapitalWeekEvent` | main/global | After the Day event that starts a new week | `weekIndex`, `catchUp` |
| `CapitalAlertEvent` | either | `api.alert(...)` | source, `Severity {INFO, WARN, CRITICAL}`, code, message |
| `CapitalAuditEvent` | async | After the nightly ledger self-check | `ok`, list of problems |
| `CapitalPolicyLimitEvent` | either | A call hit a cap or was denied | plugin, `LimitKind {ISSUE, TREASURY, DENIED}`, requested, remaining |

- **`catchUp = true`** means the boundary happened while the server was offline and is being fired late on startup. Add-ons decide what to do. For example, Bank pays no interest for those days.
- Day and Week events always fire **in order** and **exactly once per index**. Core remembers the last index it fired.

### 5.8 Example: a tiny add-on paying a daily reward
```java
@EventHandler
void onDay(CapitalDayEvent e) {
  if (e.catchUp()) return;
  for (Player p : Bukkit.getOnlinePlayers()) {
    api.transaction(this, "reward.daily", "Daily login reward")
       .issue(api.accounts().player(p.getUniqueId()), 10_00)
       .reference("daily:" + e.dayIndex() + ":" + p.getUniqueId())   // safe to retry
       .commit()
       .thenAccept(r -> { if (!r.success()) getLogger().info("Reward skipped: " + r.failure()); });
  }
}
```

## 6. Data
None. The API holds no data, and core owns all storage.

## 7. Config
None.

## 8. Commands and permissions
None.

## 9. Threading and failure handling
- **Thread-safe from anywhere:** every getter (`balance()`, `stats()`, `policy()`, `currency()`).
- **Callable from any thread:** every mutation. The call validates and applies in memory synchronously, then persists in the background.
- **Never block the main thread on a future** (`join()` or `get()`). Use `thenAccept` and then hop back to the main thread.
- **Failures are values, not exceptions.** A `TxResult` with a `Failure` is returned for insufficient funds, a policy limit, a paused economy, and so on. Exceptions only signal programmer errors, such as a `null` account or a negative amount passed to `format`.

## 10. Tests
The API has no logic of its own. Its contract is tested in **core** with `ApiContractTest`, a suite that runs every rule in §5.4 against the real implementation. Third parties can copy that suite to check their mocks.

## 11. Versioning and publishing
- **Semantic versioning.** `1.0.0` ships with core Phase 0.
  - Minor versions only *add* things (methods with default implementations, new enum values at the end, new events).
  - Major versions may break things.
- `apiVersion()` returns the running version. `isCompatible(min)` compares by semantic version.
- **Published** as a binary jar plus Javadoc with a sources jar as `com.patelheet:capitalengine-api:<version>`, to the public Maven repo at `https://patelheet30.github.io/capitalengine-api/m2` (GitHub Pages on `patelheet30/capitalengine-api`). Every public member gets a Javadoc comment.

**Done when:**
- every interface and record above exists with Javadoc
- the jar is published
- the example in §5.8 compiles against it
- core's `ApiContractTest` passes

## 12. Open questions
1. ~~Group and package ID~~ **Decided:** group `com.patelheet`, packages `com.patelheet.capitalengine.<module>`.
2. ~~Licence~~ **Decided:** GPL-3.0-only with the [CapitalEngine Add-on Exception](../ADDON-EXCEPTION.txt), so add-ons built on the API may use any licence. [API-LICENSE.txt](API-LICENSE.txt) (version 2.0) summarises this. The build copies it into the jar as `META-INF/API-LICENSE.txt` and into the Javadoc bundle.
3. **Planned for 1.x:**
   - **Shared accounts** (members, roles, permissions) for CapitalBusiness and VaultUnlocked's shared-account methods.
   - **Multiple currencies,** a `Currency` parameter overload, for CapitalNations.
