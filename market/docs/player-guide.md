# CapitalMarket Player Guide

CapitalMarket is where you sell the things you gather and buy the things you need. This guide explains how it works. Everything here is also available in game with `/market help`.

Figures such as fees and limits are the defaults. Your server's owner may have changed them, so the numbers you see in game take priority over those written here.

## Contents
1. [The basics](#the-basics)
2. [Opening the market](#opening-the-market)
3. [Selling to the market](#selling-to-the-market)
4. [Buying from the market](#buying-from-the-market)
5. [How prices work](#how-prices-work)
6. [Trading with other players](#trading-with-other-players)
7. [Your Stash](#your-stash)
8. [Market stalls](#market-stalls)
9. [The Jobs Board](#the-jobs-board)
10. [The tutorial](#the-tutorial)
11. [Playing on Bedrock](#playing-on-bedrock)
12. [Command reference](#command-reference)
13. [Questions and problems](#questions-and-problems)

## The basics
- You can trade in two ways:
  - **With the market itself.** The market always buys and sells everyday items such as ores, crops, food, wood and stone, so you can trade even when no other player is online.
  - **With other players.** You can list an item at a price you choose, or place a request for an item you want.
- Items you buy, or put up for sale, are kept in your **Stash**, not your inventory.
- Every cost is confirmed before it is taken. Your balance never goes below zero.

## Opening the market
Type `/market`. The main menu has these buttons:

| Button | What it does |
|---|---|
| **Trade Held Item** | Opens the trading page for the item in your hand. |
| **Browse Market** | Lists every item the market trades, with buy and sell prices. |
| **Search Items** | Finds an item by name. |
| **Stash** | Shows your stored items, and lets you deposit or withdraw. |
| **Open Orders** | Shows your listings and requests, and lets you cancel them. |
| **Jobs Board** | Delivery and build jobs that pay coins. |
| **Market Overview** | Price movements this week and whether the market is buying. |
| **Help** (bottom corner) | Explains how the market works, in chat. |

You can also right-click a **market stall** (a barrel with a floating "Market Stall" sign) to open the same menu.

## Selling to the market
1. Hold the item and choose **Trade Held Item**, or find it through **Browse Market** or **Search Items**.
2. On the item page, use the **green Sell** buttons: Sell 1, Sell 16, Sell 64 or Sell All.
3. A confirmation screen shows exactly how much you will receive. Choose **Confirm** to sell.

Good to know:
- The market pays the **sell price**. It does not charge a fee when you sell to it.
- The market can only buy a limited amount each day, so that new coins enter the economy at a steady pace. The item page tells you how many more units it will buy today. If the limit is reached, you will be told, and you can:
  - list the item for other players (see below), or
  - try again after the daily reset.
- If you ask to sell more than the market can buy today, it buys what it can and says so. The rest stays with you.
- Items sold come from your inventory first, and then your Stash.

### What can be traded
Plain items can be traded, as well as **enchanted books** and **potions**. These cannot be traded:
- items with a custom name or lore,
- damaged tools and armour,
- shulker boxes that contain anything,
- any item the server owner has blocked.

## Buying from the market
1. Open the item's page and use the **orange Buy** buttons: Buy 1, Buy 16 or Buy 64.
2. Check the total on the confirmation screen and choose **Confirm**.
3. The items go to your **Stash**. Withdraw them with the Stash menu or `/market take <item>` (see [Market stalls](#market-stalls)).

The market only sells what it has in stock, so a popular item can run out. When that happens, look at **Player Listings** for the item, or check again later.

## How prices work
Each item has two prices:
- **Buy price**: what you pay the market.
- **Sell price**: what the market pays you.

The buy price is always higher than the sell price. That gap is what keeps the market from being a way to make money by trading with itself, and it is why trading with other players can be better for both sides.

Prices move by themselves:
- When the market holds **lots** of an item (because players sold it), its price **falls**.
- When the market is **low** on an item, its price **rises**.
- Over time, the prices players actually trade at slowly pull the market's price towards them.

**Market Overview** shows the largest price rises and falls this week. Before the first full week of the economy there is nothing to show yet.

On servers with a bank, Market Overview also shows the current **savings rate**.

## Trading with other players
Open an item's page for three options:

- **Player Listings** shows what other players are selling (cheapest first) and what they are asking for. Click an entry to buy, or sell, up to and including that entry.
- **List at Custom Price** offers an item at a price you choose. If someone already wants it at that price or more, it sells straight away. Otherwise it waits as an **order**.
- **Place Wanted Order** requests an item at the price you choose. Your coins for it are **held** until it is filled or you cancel it.

Rules:
- **Fee:** the seller pays a **2%** fee on a sale between players. The buyer pays nothing extra.
- **Players come first.** At the same price, a player's order is filled before the market's.
- **Trades happen at the waiting order's price.** If you list at 5 coins and a request for 8 coins is waiting, you receive 8.
- **You cannot trade with yourself.**
- **Limits:** you can have up to **20** open orders, and each order lasts **14 days**. When an order expires or is cancelled, your items or coins come back.
- Items you have listed are **locked** in your Stash until they sell or you cancel. They show as "listed for sale".

Find your orders under **Open Orders** or with `/market orders`, and cancel with a click or `/market cancel <id>`.

## Your Stash
The Stash holds your items for the market. It fills when you **buy** items, **list** items for sale, or **receive** items from a job.

- **Capacity:** 54 stacks (one double chest).
- **Depositing** works anywhere: use **Deposit Held Item** in the Stash menu, or sell and list straight from your inventory.
- **Withdrawing** takes items back into your inventory. Click an item in the Stash menu to withdraw up to one stack, or type `/market take <item> [amount]`. If your inventory is full, the rest stays in the Stash.
- Items that are **listed for sale** cannot be withdrawn until the order ends.

## Market stalls
A **market stall** is a barrel with a floating "Market Stall" sign above it. Right-click the barrel to open the market.

On most servers you can **only withdraw items from your Stash near a stall** (within about 6 blocks). Everything else, including buying, selling and listing, works anywhere. If you see "Items can only be withdrawn from the Stash at a market stall", walk to the nearest stall. Ask the server team where the stalls are.

Server administrators place stalls with `/market admin stall create` and remove them with `/market admin stall remove`. A stall's barrel cannot be broken by players.

## The Jobs Board
The Jobs Board pays coins for useful work. Open it with `/jobs` or the **Jobs Board** button.

### Delivery jobs
A delivery job asks for a number of items at a price per item.
- Open a job, then choose **Deliver 1**, **Deliver 16**, **Deliver 64** or **Deliver All**. Items come from your inventory first, then your Stash.
- You are **paid immediately** for each item delivered.
- Jobs are posted by:
  - **players**: the items you deliver go to the poster's Stash. Their coins are held safely, so you will always be paid.
  - **the server**: for community projects, or for items the town has more of than it needs. These jobs are posted by **The Town**. Server jobs pay a little more than the market's sell price, and the items are used up. For every item you bring to a job for items the town has too many of, it also uses up one of the server's own spare items, so that item's price recovers sooner.
- Server jobs have a daily payment budget. If it runs out, you will be told they resume tomorrow.
- Some projects collect items in a **project chest**. If it is full, deliveries wait until there is room.

### Posting a job
Use **Post Delivery Job** while holding the item you want, then enter how many you need and what you will pay for each. The full payment is held until the job is done. If nobody completes it within **7 days**, or you cancel, the unused coins are returned.

### Build jobs
A build job pays a set amount for something to be built.
1. A player posts it with a description and a payment (**Post Build Job**).
2. Another player chooses **Accept Job**.
3. When the build is finished, the poster chooses **Mark Complete** and the builder is paid.
4. If there is a disagreement, either side can choose **Report a Problem**. Staff then decide whether to pay the builder or refund the poster.

## The tutorial
New players are guided through five short steps with a bar at the top of the screen. Each step pays a small reward, once:
1. View your balance with `/money`.
2. Sell any item through `/market`.
3. Deliver items to any job.
4. Open `/economy`.
5. Open `/bank` (only on servers that have a bank).

Steps must be done in order. You can skip the tutorial at any time with `/market tutorial skip`.

## Playing on Bedrock
Everything works on Bedrock through Geyser:
- The menus are ordinary chest menus.
- Where a Java player types a number or a price in chat, Bedrock players on a server with Floodgate get a **form** to fill in. Without it, you are asked in chat, and you can type `cancel` to stop.
- Confirmations appear as a Yes/No form. If none appears, type `/market yes` within 30 seconds.
- Details such as buy and sell prices are shown in item names and chat as well as in the item description, since descriptions do not always show on Bedrock.
- Names with the Floodgate prefix (for example `.Steve`) work everywhere.

## Command reference
| Command | What it does |
|---|---|
| `/market` | Opens the market menu. |
| `/market help [topic]` | Explains how the market works. Topics: start, sell, buy, orders, stash, jobs, stalls, prices, commands. |
| `/market sell [amount\|all]` | Sells the item in your hand to the market, after confirmation. |
| `/market buy <item> <amount>` | Buys from the market, after confirmation. |
| `/market list <amount> <price>` | Lists the item in your hand for other players at a price each. |
| `/market wanted <item> <amount> <price>` | Requests an item from other players. |
| `/market orders` | Shows your open orders. |
| `/market cancel <id>` | Cancels one of your orders. |
| `/market stash` | Shows what is in your Stash. |
| `/market take <item> [amount]` | Moves items from your Stash to your inventory. |
| `/market price [item]` | Shows an item's prices and weekly change. |
| `/market yes` | Confirms the last question if you cannot click. |
| `/market tutorial skip` | Skips the tutorial. |
| `/economy` | Opens Market Overview. |
| `/jobs` | Opens the Jobs Board. |
| `/jobs list` | Lists open jobs in chat. |
| `/jobs post <amount> <price>` | Posts a delivery job for the item in your hand. |
| `/jobs build <price> <description>` | Posts a build job. |
| `/jobs deliver <id> [amount]` | Delivers to a job. |
| `/jobs accept <id>` | Accepts a build job. |
| `/jobs done <id>` | Marks your build job complete and pays the builder. |
| `/jobs problem <id>` | Reports a problem with a build job. |
| `/jobs cancel <id>` | Cancels your job and refunds the held payment. |

Prices can be written as `12`, `1.5`, `1.5k` or `2m`. Item names may be written with spaces, for example `/market buy iron ingot 64`.

## Questions and problems

**"The market has reached its purchasing budget for today."**
The market can only buy a fixed amount per day. List the item for other players, or try again after the daily reset. Market Overview shows whether the market is buying.

**"Items can only be withdrawn from the Stash at a market stall."**
Walk to a market stall (a barrel with a floating sign) and try again.

**"Your Stash is full."**
Sell, list or withdraw something first. The Stash holds 54 stacks.

**"This item cannot be traded."**
Named, lore, damaged and non-empty shulker items cannot be traded here. Enchanted books and potions can.

**"The price has changed."**
Prices move as the market's stock changes. Look at the new total and confirm again.

**Where did my items go after I listed them?**
They are locked in your Stash until the order sells, expires or is cancelled. Check **Open Orders**.

**My order disappeared.**
Orders last 14 days. When one expires, the items or coins return to you automatically.

**Nobody buys my listing.**
Check **Player Listings** to compare your price with others, and with the market's buy price. A price just below the market's buy price usually sells quickly.

**Can I lose coins by trading?**
Only if you buy at one price and sell lower. The market never takes coins without a confirmation, and held coins for orders and jobs are returned if they are cancelled or expire.
