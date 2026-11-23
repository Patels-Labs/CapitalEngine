# CapitalBank Player Guide

CapitalBank keeps your savings safe, lets you borrow a little when you need it, and collects a small daily upkeep for very large pieces of land. This guide explains how it works. Everything here is also available in game with `/bank help`.

Figures such as rates and limits are the defaults. Your server's owner may have changed them, so the numbers you see in game take priority over those written here.

## Contents
1. [The basics](#the-basics)
2. [Opening the bank](#opening-the-bank)
3. [Savings](#savings)
4. [Loans](#loans)
5. [Land upkeep](#land-upkeep)
6. [Playing on Bedrock](#playing-on-bedrock)
7. [Command reference](#command-reference)
8. [Questions and problems](#questions-and-problems)

## The basics
- **Savings** earn a little interest every day.
- You can have **one loan** at a time, paid back a little each day.
- **Land upkeep** is a small daily cost for land above a free allowance.
- Anything that costs coins asks you to confirm first. The bank never takes you below zero.

## Opening the bank
Type `/bank`. The menu has these buttons:

| Button | What it does |
|---|---|
| **Savings** | Shows your savings and the savings rate, and lets you put coins in or take them out. |
| **Loan** | Shows how much you can borrow, or how much is left to pay on your loan. |
| **Land Upkeep** | Shows how much land you own, what it costs each day, and anything owed. |
| **Help** | Shows the in-game help. |

## Savings
- Coins in savings earn interest **every day**. The rate is shown per week, for example "Savings rate: 2% a week".
- The interest is added to your savings, so your savings grow over time.
- Interest is paid on the first **10,000 coins** of your savings.
- You can put coins in and take them out at any time. There is no waiting and no fee.
- Your savings are safe: no other plugin or player can take coins out of them, and land upkeep never comes out of your savings.
- When you join, the bank tells you how much your savings grew in the last week.
- On some days the bank has a limited amount to pay in interest. When that happens, everyone's interest is reduced by the same share.

In the Savings menu, the buttons put in or take out 10, 100 or 1,000 coins, everything, or an amount you type.

## Loans
- **Your limit** starts at **500 coins**. Each loan you pay back with no missed days raises it by half, up to **5,000 coins**.
- **Before your first loan** you need **2 hours** of playtime on the server.
- **Paying back:** a loan is paid back over **7 days**. The interest is a fixed amount agreed at the start. For example, borrowing 500 coins at 5% a week costs 25 coins, so you pay back 75 coins a day for 7 days (525 coins in total). The confirmation shows exactly this before you agree.
- **Each day** the day's payment is taken from your coins automatically.
- **If you do not have enough coins** on a day, nothing bad happens. There is no penalty and no extra interest. The amount you are behind is taken as soon as you receive coins, and you cannot borrow again until the loan is paid back.
- **Paying back early:** use **Pay back everything I can** in the Loan menu, or `/bank repay`. The interest agreed at the start stays the same.
- **A loan that is never paid back** is closed after 28 days behind. Your limit returns to 500 coins and you can borrow again after 14 days.
- If you stop playing for a long time (60 days or more), your loan simply waits for you.
- Sometimes the bank has lent out all it can. If so, try again the next day.

## Land upkeep
Land upkeep applies on servers that use a land claim plugin (GriefPrevention, Lands or WorldGuard).
- The first **10,000 blocks²** of land you own (an area of 100 by 100 blocks) are **free**.
- Above that, land costs **1 coin a day for every 100 blocks²**. For example, 18,400 blocks² costs 84 coins a day.
- Upkeep is taken from your coins automatically every day, and as soon as you receive coins if any is owed.
- **If more than 3 days of upkeep is owed,** your land is **frozen**: you and the players you trust there cannot build, break blocks or open chests on it. Visitors are not affected.
- **Nothing is ever deleted or unclaimed.** The land unfreezes as soon as the upkeep is paid.
- If you are away for more than **14 days**, no upkeep builds up until you return.

The Land Upkeep menu shows, for example: "Your land: 18,400 blocks²", "Free: 10,000 blocks²", "Daily upkeep: 84 coins", "Owed: nothing ✔".

## Playing on Bedrock
- The bank menus work the same on Bedrock.
- When you are asked for an amount, a form opens if the server supports it; otherwise type the number in chat.
- Clickable chat buttons may not work on Bedrock. Every one of them also tells you what to type instead, such as `/bank yes`.

## Command reference
| Command | What it does |
|---|---|
| `/bank` | Opens the bank menu. |
| `/bank deposit <amount>` or `/bank deposit all` | Puts coins into savings. |
| `/bank withdraw <amount>` or `/bank withdraw all` | Takes coins out of savings. |
| `/bank loan` | Shows what you can borrow, or your loan. |
| `/bank loan <amount>` | Borrows coins (asks you to confirm). |
| `/bank repay [amount]` or `/bank repay all` | Pays towards your loan (asks you to confirm). |
| `/bank upkeep` | Shows your land upkeep. |
| `/bank yes` | Confirms what you were just asked. |
| `/bank help [topic]` | Help. Topics: start, savings, loans, upkeep, commands. |

## Questions and problems
- **"The bank is having a nap."** The server's economy is briefly unavailable. Try again in a minute; nothing was taken.
- **"The bank has lent out all it can today."** Try again tomorrow.
- **My land is frozen.** Open `/bank upkeep` to see what is owed. It is paid automatically when you have coins, and the land unfreezes straight away.
- **I am behind on my loan.** It is taken automatically when you have coins. Until then you cannot borrow again, but nothing else happens.
