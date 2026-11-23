# CapitalBank — Plan

> **Artifact:** `CapitalBank.jar` (optional add-on) · **Depends on:** CapitalEngine ([../core/Plan.md](../core/Plan.md)) via [../api/Plan.md](../api/Plan.md)
> **Parent plan:** [../Plan.md](../Plan.md) · **Works with or without** [CapitalMarket](../market/Plan.md)

## 0. Build status (what exists, and where the code differs from this plan)
**Built and tested (`./gradlew :bank:test`):** savings (deposit, withdraw, daily interest with cap, budget scaling and the core policy-limit stop), one loan per player (credit limit and growth, playtime gate, loan-book cap, payout, daily collection, arrears and OVERDUE, early repayment, write-off and cooldown, dormant loans), land upkeep (daily charge, automatic collection when coins arrive, warning, freeze and unfreeze, vacation rule) with GriefPrevention, Lands and WorldGuard sources, the `IndicatorProvider`, the bank check, crash recovery of every in-flight row, chest menus, `/bank` commands, Floodgate forms, PlaceholderAPI, `/bank help` and [docs/player-guide.md](docs/player-guide.md). The tests use the real CapitalEngine and no CapitalMarket; a fake `MonetaryPolicy` and a fake `ClaimSource` stand in for them.

**Deliberate differences from the sections below:**
- **Fewer classes (§4).** Every formula (rates, interest, loan maths, credit limit, upkeep) is in one pure class, `math/BankMath`, with no `org.bukkit` import. `Savings`, `InterestRun`, `Loans`, `Upkeep` and `Rates` are sections of `BankService`. The freeze listener and the indicator provider live in `CapitalBankPlugin`; the four menus are one `Menus` class. `ClaimSource` and its three implementations are in `upkeep/`, and the database is `storage/Store`.
- **One worker thread instead of "100 players per tick" (§9).** As in Market, every service call runs on one worker, which may wait on API futures; the main thread never waits. Interest is sent 100 transactions at a time, so a policy limit stops the run within one batch. A day that is interrupted resumes on the next start from a durable `day_runs` marker (written, with its catch-up flag, before any money moves); a resumed catch-up day still pays nothing.
- **`ClaimSource` (§4, §5.4)** has `areas()` (every owner's area at once, read on the main thread at each Day event), `ownerAt(location)` and `trusted(location, player)`. The freeze blocks building, breaking and opening containers for the owner and for players the claim plugin trusts there (GriefPrevention container trust or better, Lands trusted players, WorldGuard members and owners); visitors are never affected. Lands area is `getChunksAmount() × 256`. WorldGuard counts nested regions separately.
- **Façade (§5.6).** Deposits, withdrawals and repayments return `Result(amount, error)` (error is a messages.yml key) rather than `TxResult`. `offer` and `borrow` take the player's playtime in minutes, read on the main thread. `upkeep` takes the player's current area. `deposit`/`withdraw`/`repay` with amount ≤ 0 mean "all".
- **Playtime and dormancy aren't in the API.** Playtime comes from the vanilla `PLAY_ONE_MINUTE` statistic. "Dormant" (60 days, core's default) and the upkeep vacation rule (14 days) use the bank's own last-seen day, recorded on join, quit and at each Day event. Both are marked `// ponytail:` for when the API exposes core's activity tracking.
- **Overdue loans count days, not a date (§5.3).** `overdue_days` grows by one on each collection day the loan is still behind and resets when it catches up; a loan is written off when it passes `write-off-days`. Because dormant loans are skipped, their write-off clock stops by itself.
- **Coins arriving (§5.4)** also pay loan arrears, not only upkeep. Transactions made by CapitalBank itself (loan payouts, savings withdrawals) don't trigger it. Within the one-minute debounce, a single follow-up run is scheduled for when the minute is up.
- **Upkeep collection** is a `move(player → Treasury)` with reference `upk:<uuid>:<seq>` (the `collect` shortcut has no reference). Its amount is saved as `pending` first, so recovery can finish it.
- **Data (§6):** see the schema in §6, which is updated. In short: `loans` has `overdue_days`, `missed` and `last_day` (the last collection day); `loan_payments` has its own id, a `kind` (`DAY` or `PAY`), the day's `due`, a `status` and a unique `reference`; the credit column is `credit_limit` (`limit` is an SQL keyword); `upkeep` has `seq` and `pending`; `players` holds last-seen days and who uses savings, so the interest run knows whose accounts to read; `bank_daily` is `day_runs` and keeps only what can't be read from `loans`.
- **The bank check (§6)** also checks that the interest the bank recorded for the last day equals what the ledger paid. The bank holds no escrow and stores no savings balances, so there is nothing else to reconcile for savings.
- **Write-off warning:** more than 10% of loans written off in a week raises a `WARN` on the Week event.
- **`/bank admin unfreeze`** sets `due` to 0 and logs it to the console; no transaction is made, because the Treasury never received those coins.
- **PlaceholderAPI** also answers `%capitalbank_loan%` (the root plan's name) with the same value as `%capitalbank_loan_left%`.
- **Dependencies:** GriefPrevention 18.0.0 and LandsAPI 7.25.4 come from JitPack, WorldGuard 7.0.17 from EngineHub; the repositories are declared in `bank/build.gradle.kts` and limited to those groups. WorldGuard 7.0.18 and later need Java 25, so 7.0.17 is the newest that compiles with `release = 21`. All three are `compileOnly` and `softdepend`.
- **Config:** `rates.max-*` also caps the config rates, not only the policy's.

**Not tested yet:** a real server (1.21.11, 26.1.x, 26.2), a Bedrock player through Geyser, the three real claim plugins (their sources are compiled against the real APIs but only exercised through a fake), and MySQL/MariaDB and PostgreSQL (the schema exists for both; tests run on SQLite). `sim` now runs the bank every day for months (`small-20`, `with-trade`): the bank check, the interest caps and budget, and "no loan outlives its term plus the write-off days" hold on every day of every run, and no bank setting moves money growth much (see [sim/RESULTS.md](../sim/RESULTS.md) §3: upkeep goes to the Treasury and is spent again).

## 1. Purpose and scope
CapitalBank is the server's simple, kid-safe bank. It covers:
1. **Savings:** coins in the bank earn a little interest every day, and are safe from other plugins.
2. **One simple loan:** borrow a small amount and pay it back over a few days. The limit grows when you repay on time.
3. **Land upkeep:** claimed land costs a small daily amount above a free allowance. It's the server's main money sink.
4. **An `IndicatorProvider`** for dashboards.

## 2. Not in scope
- **Player-run banks, credit scores, collateral, debt collectors and bankruptcy.** Those are future add-ons (CapitalBanks+ and CapitalCredit).
- **Towny upkeep.** Towny already charges its own upkeep through Vault, and core's per-plugin policy routes that into the Treasury.
- **Setting rates.** Rates come from `MonetaryPolicy` (CapitalMarket's autopilot) when one is registered, otherwise from config.

## 3. Depends on
- **Required:** `CapitalEngine` (API ≥ 1.0).
- **Soft:**
  - `GriefPrevention`, `Lands`, `WorldGuard`: upkeep
  - `PlaceholderAPI`
  - `Floodgate`
- **No other add-on.** The bank asks the `ServicesManager` for *any* `MonetaryPolicy`; it doesn't care which plugin provides it.

## 4. Package layout and key classes
```
com.patelheet.capitalengine.bank
├─ CapitalBankPlugin          wiring, events, the freeze listener, IndicatorProvider "capitalbank"
├─ BankService                public façade for GUIs, commands, simulator (§5.6): savings, interest run, loans, upkeep, day run, recovery, check
├─ Actions / Menus / Menu     what a player can do; chest menus (Bank, Savings, Loan, Land Upkeep, Yes/No)
├─ BankCommand                /bank and /bank admin
├─ BankForms                  the only class touching Floodgate
├─ BankExpansion              PlaceholderAPI
├─ Messages / FileUpdater     messages.yml; config and messages kept current on upgrade
├─ math/BankMath              rates, interest and scaling, loan terms and split, credit limit, upkeep formula   (pure Java)
├─ upkeep/
│  ├─ ClaimSource             interface: areas(), ownerAt(location), trusted(location, player)
│  └─ GriefPreventionSource / LandsSource / WorldGuardSource   (one per installed plugin)
└─ storage/Store              own DB, migrations (bank-db/<dialect>/V<n>__*.sql)
```
`ClaimSource` has one implementation per claim plugin, which is why it's an interface. Each one is only loaded when its plugin is present.

## 5. How it works

### 5.1 Rates
`Rates.savingsPerDay()` and `Rates.loanPerDay()`:
1. If `api.monetaryPolicy()` is present, use its values, capped by this plugin's config `max` values (the owner's safety net).
2. Otherwise use `rates.savings-weekly / 7` and `rates.loan-weekly / 7` from config.

**Players always see rates per week,** for example "Savings rate: 2% a week".

### 5.2 Savings
- **Each player's savings are a core plugin account,** `capitalbank:savings:<uuid>`.
  - Every coin stays in the core ledger, so it's audited.
  - **Only CapitalBank can take money out of it.** The API's ownership rule guarantees that, so other plugins can't touch savings.
- **Deposit** = `transfer(player → savings)`. **Withdraw** = `transfer(savings → player)`.
  - Both are instant, with no lock-in.
  - The amount buttons are 10 / 100 / 1,000 / All.
- **Interest** (`InterestRun`, at each **Day** event that isn't a catch-up):
  ```
  eligible_i = min(savingsBalance_i, interest-cap)           # default cap 10,000
  owed_i     = floor(eligible_i × savingsPerDay)
  budget     = monetaryPolicy.budget("savings") orElse config savings-daily-budget
  scale      = min(1, budget / Σ owed_i)                    # everyone scaled equally if short
  pay_i      = floor(owed_i × scale)
  ```
  - Each payment is committed as `transaction("bank.interest", "Daily savings interest").serverPay(savings_i, pay_i).reference("int:<day>:<uuid>")`, so it's idempotent and paid **into the savings account**, where it compounds.
  - **If `serverPay` fails with `POLICY_LIMIT`** partway through (core's cap is tighter than the budget), the run stops, the remaining players get nothing that day, and an `INFO` alert says so.
  - **No interest for catch-up days** (the server was off).
- **Players see** "+12 coins interest" in the savings history, and once a day on login a line like "Your savings grew by 84 coins this week 🌱".

### 5.3 Loans
- **One active loan per player.**
- **Credit limit** (`CreditLimit`):
  - It starts at `loans.start-limit` (500).
  - Each loan **fully repaid with no missed days** multiplies it by `1 + loans.growth` (0.5), up to `loans.max-limit` (5,000).
  - New players need `loans.min-playtime-hours` (2) of active playtime, measured by core's activity tracking, before they can borrow.
- **Terms:**
  - The player picks an amount up to their limit.
  - The term is `loans.term-days` (7). Interest is **flat**: `interest = principal × loanPerDay × termDays`.
  - The instalment is `ceil((principal + interest) / termDays)`; the last day takes only what is left, so no coin is lost to rounding.
  - The confirmation screen says exactly that: "Borrow 500 coins, pay back 75 coins a day for 7 days (525 total). ✔ / ✖".
- **Payout:** `transaction("bank.loan_payout").issue(player, principal).reference("loan:<id>:payout")`. **The loan creates the money,** just like real bank lending.
- **Bank-wide cap:** before paying out, the bank checks that Σ outstanding principal + the new principal ≤ `loans.loan-book-max`. If not: "The bank has lent out all it can today, try tomorrow!"
- **Daily collection** (at each Day event that isn't a catch-up), for each active loan:
  ```
  take      = min(instalment + arrears, player wallet)                  # never more than they have
  principal = floor(take × principalLeft/(principalLeft+interestLeft))   ;   interestPart = take − principal   # by what is still owed, so the last payment clears both exactly
  tx: destroy(player, principal) + move(player → Treasury, interestPart) , reference "loan:<id>:day:<day>"
  ```
  - The principal part is **destroyed**, so the money the loan created goes away again. The interest goes to the Treasury.
  - **If `take` is less than the instalment,** the shortfall becomes `arrears`, the loan becomes `OVERDUE` and **borrowing is paused**. The player gets one friendly message a day: "You're 40 coins behind on your loan. It'll be taken automatically when you have coins."
  - **No penalties, no extra interest, no seized items,** and the player never goes below zero.
- **Early repayment:** `/bank repay [amount|all]`. Remaining interest is **not** forgiven, which keeps things simple; the GUI shows the remaining total.
- **Write-off:**
  - When a loan is overdue for more than `loans.write-off-days` (28), it's written off. The unpaid principal stays in circulation as created money, which the ledger already shows.
  - The player's limit resets to `start-limit`, and borrowing resumes after `loans.cooldown-days` (14).
  - An `INFO` alert is raised. More than 10% of loans being written off in a week raises a `WARN`.
- **Players who become dormant** (inactive 60+ days) **keep their loan frozen**: there's no collection, and the write-off clock pauses. It resumes when they return.

### 5.4 Land upkeep
- **The area each player owns** comes from every installed `ClaimSource`:
  - **GriefPrevention:** the 2D area of claims the player owns.
  - **Lands:** chunks × 256 for lands the player owns.
  - **WorldGuard:** the 2D area of regions where the player is an *owner*.
  - **Towny** is skipped (§2).
- **Daily charge** (at each Day event that isn't a catch-up):
  ```
  billable = max(0, area − upkeep.free-blocks)                 # default 10,000 blocks² (100×100) free
  charge   = ceil(billable / 100) × upkeep.per-100-blocks       # default 1 coin per 100 blocks²/day
  due     += charge
  take     = min(due, wallet) → collect(player, take, "bank.upkeep") ; due −= take
  ```
  - **Coins are also taken automatically when they arrive.** The plugin listens to `CapitalTransactionEvent` for deposits into players with `due > 0` and runs a `collect`. It's debounced to once per minute per player.
- **Escalation**, which is kid-friendly:
  - **Day 1 with coins owed:** a chat message and a GUI badge: "Your land upkeep is 30 coins behind."
  - **`due` above 3 days' charge:** the land is **frozen**. Building, breaking and container access in that owner's claims are blocked for the owner and their trusted players, with the message "This land is frozen until upkeep is paid — /bank upkeep".
  - **Nothing is ever deleted or unclaimed.**
  - **Unfreezing** is automatic the moment `due` reaches 0.
- **Vacation rule:** players offline for more than `upkeep.pause-after-offline-days` (14) don't build up upkeep. It restarts when they log in.
- **The upkeep GUI shows:** "Your land: 18,400 blocks². Free: 10,000. Daily upkeep: 84 coins. Owed: 0 ✔".

### 5.5 Indicators (`IndicatorProvider` id `capitalbank`)
Cached and refreshed at each Day event:
- total savings
- savers count
- interest paid today
- scale factor today
- loan book (outstanding principal)
- active, overdue and written-off loans
- upkeep collected today
- upkeep owed in total
- frozen owners

### 5.6 `BankService` (public façade)
```java
CompletableFuture<Result> deposit(UUID p, long amount);      CompletableFuture<Result> withdraw(UUID p, long amount);   // amount ≤ 0: all
long savings(UUID p);
CompletableFuture<LoanOffer> offer(UUID p, long playedMinutes);              // limit, weekly rate, term, or the reason it's not allowed
BankMath.Terms preview(long amount);                                         // interest, instalment, total at today's rate
CompletableFuture<LoanResult> borrow(UUID p, long amount, long playedMinutes);
CompletableFuture<Result> repay(UUID p, long amount);                        // amount ≤ 0: everything left the wallet can cover
CompletableFuture<Optional<Loan>> loan(UUID p);
CompletableFuture<UpkeepView> upkeep(UUID p, long area);
CompletableFuture<Void> onDay(long day, boolean catchUp, Snapshot s);         // also driven directly by tests and the simulator
CompletableFuture<List<String>> check();
```
`Result(amount, error)` carries a messages.yml key as the error. Playtime and the player's area are read on the main thread by the caller.
GUIs, commands and the simulator all use this façade.

## 6. Data (own database, `plugins/CapitalBank/bank.db` by default)
```
loans        (id PK, uuid, principal, interest, instalment, term_days, start_day, paid_principal, paid_interest, arrears,
              status PENDING|ACTIVE|OVERDUE|PAID|WRITTEN_OFF|FAILED, overdue_days, missed, last_day, closed_day NULL)
loan_payments(id PK, loan_id, day_index, kind DAY|PAY, due, principal, interest, reference UNIQUE, status PENDING|DONE)
credit       (uuid PK, credit_limit, clean_repayments, paused_until_day NULL)
upkeep       (uuid PK, area, due, last_charged_day, frozen, last_warned_day, seq, pending)
players      (uuid PK, last_seen_day, saver)
day_runs     (day_index PK, catch_up, done, interest_owed, interest_paid, scale, upkeep_collected)
```
Savings balances are **not** stored here. They live in core as accounts.

References: payout `loan:<id>:payout`, daily collection `loan:<id>:day:<day>`, early repayment and coins-arrived payments `loan:<id>:pay:<n>`, interest `int:<day>:<uuid>`, upkeep `upk:<uuid>:<seq>`. Each PENDING row (loan, payment, upkeep `pending`) is written with its exact amounts before its transaction is sent; on startup a row whose reference is in the ledger is finished, and one whose reference isn't is dropped.

**Check** (at each Day event and on `/bank admin check`): for each active loan, the payout and every collection reference must exist in core (`findByReference`), and `paid_principal` must equal the sum of the destroyed legs in those records. The interest recorded for the last day run must equal what the ledger paid under its `int:` references. A mismatch raises a `CRITICAL` alert.

## 7. Config (abridged)
```yaml
storage: { type: sqlite }
rates: { savings-weekly: 0.02, loan-weekly: 0.05, max-savings-weekly: 0.05, max-loan-weekly: 0.15 }   # used if no MonetaryPolicy; max always applies
savings: { interest-cap: 10000, savings-daily-budget: 2000 }
loans: { start-limit: 500, growth: 0.5, max-limit: 5000, term-days: 7, loan-book-max: 200000,
         min-playtime-hours: 2, write-off-days: 28, cooldown-days: 14 }
upkeep: { enabled: true, free-blocks: 10000, per-100-blocks: 1, freeze-after-days: 3, pause-after-offline-days: 14 }
```

## 8. Commands and permissions

| Command | Permission (default) | What |
|---|---|---|
| `/bank` | `capitalbank.use` (all) | Bank GUI: Savings, Loan, Land upkeep |
| `/bank deposit <amount\|all>` / `/bank withdraw <amount\|all>` | `capitalbank.use` | Savings |
| `/bank loan [amount]` / `/bank repay [amount\|all]` | `capitalbank.loans` (all) | Loans (with confirmation) |
| `/bank upkeep` | `capitalbank.use` | Land upkeep view |
| `/bank admin loans [player]` | `capitalbank.admin` (op) | Inspect loans |
| `/bank admin forgive <player>` | op | Writes off the loan now (logged) |
| `/bank admin unfreeze <player>` | op | Clears `due` and unfreezes (the Treasury absorbs it, logged) |
| `/bank admin check` / `reload` | op | Maintenance |

PlaceholderAPI: `%capitalbank_savings%`, `%capitalbank_rate%` ("2% a week"), `%capitalbank_loan_left%`, `%capitalbank_upkeep_due%`.

## 9. Threading and failure handling
- **Day-event runs** (collection, upkeep, interest) run on the bank's worker thread, so the main thread never stalls; only the claim areas and the online list are read on the main thread first. Each player's transaction has a reference, and `day_runs` records that a day has started before any money moves, so **a run that's interrupted resumes safely** on the next start: it re-runs the day, and duplicates are skipped.
- **`ECONOMY_PAUSED`** stops the run, which retries in 5 minutes. Players see "The bank is having a nap, try again in a minute!"
- **Freezing** uses fast in-memory lookups: a set of frozen owners, plus `ClaimSource.ownerAt` lookups that the claim plugins already cache.
- **If a claim plugin is removed,** its source simply stops reporting area. Frozen state from it is cleared on the next Day event.

## 10. Tests
- **Unit tests** (pure Java):
  - `LoanMath`: instalments, rounding (no coin lost), principal/interest split
  - `CreditLimit`: growth, cap, pause, reset
  - `InterestRun`: cap, scaling, zero budget
  - upkeep formula: free allowance, rounding
- **Integration** (MockBukkit plus core):
  - a loan payout increases money supply, and full repayment returns it exactly to the start
  - write-off leaves the ledger consistent
  - savings can't be withdrawn by another mock plugin (`NOT_ACCOUNT_OWNER`)
  - rates come from config without `MonetaryPolicy`, and switch when a fake `MonetaryPolicy` is registered
  - a Day run interrupted halfway then re-run gives no double interest
  - the freeze listener blocks the owner and trusted players only

## 11. Done when
- [ ] With **no CapitalMarket installed,** savings interest, loans and upkeep all work from config rates.
- [ ] With CapitalMarket installed, the rates follow the autopilot without restarting.
- [ ] A player can't go below zero through any bank path, and a loan can never trap them. At worst, borrowing pauses.
- [ ] Upkeep freezes and unfreezes correctly on GriefPrevention, Lands and WorldGuard, and never deletes anything.
- [x] The bank check passes after a simulated month (see `sim`).

## 12. Open questions
1. **Should early repayment refund unused interest?** *No, for simplicity.* It could be reconsidered for the Realistic preset.
2. **Should upkeep also come out of savings when the wallet is empty?** *No.* Savings stay safe, and kids keep a clear mental model.
3. **Should frozen land also block *visitors* from opening containers?** *No.* Only the owner and their trusted players are affected.
