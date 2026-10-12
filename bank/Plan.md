# CapitalBank — Plan

> **Artifact:** `CapitalBank.jar` (optional add-on) · **Depends on:** CapitalEngine ([../core/Plan.md](../core/Plan.md)) via [../api/Plan.md](../api/Plan.md)
> **Parent plan:** [../Plan.md](../Plan.md) · **Works with or without** [CapitalMarket](../market/Plan.md)

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
├─ CapitalBankPlugin
├─ BankService                public façade for GUIs, commands, simulator (§5.6)
├─ savings/
│  ├─ Savings                 deposit / withdraw (transfers to/from the player's savings account)
│  └─ InterestRun             daily interest with budget scaling             (pure Java core)
├─ loans/
│  ├─ Loans                   apply / disburse / collect / overdue / write-off
│  ├─ LoanMath                instalments, principal/interest split          (pure Java)
│  └─ CreditLimit             start limit, growth, pauses                    (pure Java)
├─ upkeep/
│  ├─ Upkeep                  daily charge, due balance, warnings, freeze
│  ├─ ClaimSource             interface: ownedArea(uuid), ownerAt(location)
│  ├─ GriefPreventionSource / LandsSource / WorldGuardSource   (one per installed plugin)
│  └─ FreezeListener          blocks building in a frozen owner's claims
├─ rates/Rates                MonetaryPolicy if present, else config
├─ BankIndicators             IndicatorProvider "capitalbank"
├─ gui/                       BankMenu, SavingsMenu, LoanMenu, UpkeepMenu
└─ storage/                   own DB, migrations, repos
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
  - The instalment is `(principal + interest) / termDays`.
  - The confirmation screen says exactly that: "Borrow 500 coins, pay back 75 coins a day for 7 days (525 total). ✔ / ✖".
- **Payout:** `transaction("bank.loan_payout").issue(player, principal).reference("loan:<id>:payout")`. **The loan creates the money,** just like real bank lending.
- **Bank-wide cap:** before paying out, the bank checks that Σ outstanding principal + the new principal ≤ `loans.loan-book-max`. If not: "The bank has lent out all it can today, try tomorrow!"
- **Daily collection** (at each Day event that isn't a catch-up), for each active loan:
  ```
  take      = min(instalment + arrears, player wallet)                  # never more than they have
  principal = take × principal/(principal+interest)   ;   interestPart = take − principal
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
CompletableFuture<TxResult> deposit(UUID p, long amount);   CompletableFuture<TxResult> withdraw(UUID p, long amount);
long savings(UUID p);
LoanOffer offer(UUID p);                                     // limit, rate, instalment preview, or reason not allowed
CompletableFuture<LoanResult> borrow(UUID p, long amount);   CompletableFuture<TxResult> repay(UUID p, long amount);
Optional<LoanView> loan(UUID p);
UpkeepView upkeep(UUID p);
```
GUIs, commands and the simulator all use this façade.

## 6. Data (own database, `plugins/CapitalBank/bank.db` by default)
```
loans        (id PK, uuid, principal, interest, instalment, term_days, start_day, paid_principal, paid_interest,
              arrears, status ACTIVE|OVERDUE|PAID|WRITTEN_OFF, overdue_since NULL, closed_day NULL)
loan_payments(loan_id, day_index, principal, interest, reference, PK(loan_id, day_index))
credit       (uuid PK, limit, clean_repayments, paused_until_day NULL)
upkeep       (uuid PK, area, due, last_charged_day, frozen BOOL, last_warned_day)
bank_daily   (day_index PK, interest_owed, interest_paid, scale, upkeep_collected, loans_issued, loans_repaid, written_off)
```
Savings balances are **not** stored here. They live in core as accounts.

**Check** (at each Day event and on `/bank admin check`): for each active loan, the payout and every collection reference must exist in core (`findByReference`), and `paid_principal` must equal the sum of the destroyed legs in those records. A mismatch raises a `CRITICAL` alert.

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
- **Day-event runs** (interest, collection, upkeep) process players in batches of 100 per tick, so a large server doesn't stall. Each player's transaction has a reference, so **a run that's interrupted resumes safely** on the next start: it re-runs the day, and duplicates are skipped.
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
- [ ] The bank check passes after a simulated month (see `sim`).

## 12. Open questions
1. **Should early repayment refund unused interest?** *No, for simplicity.* It could be reconsidered for the Realistic preset.
2. **Should upkeep also come out of savings when the wallet is empty?** *No.* Savings stay safe, and kids keep a clear mental model.
3. **Should frozen land also block *visitors* from opening containers?** *No.* Only the owner and their trusted players are affected.
