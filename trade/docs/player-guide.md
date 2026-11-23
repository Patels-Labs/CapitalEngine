# CapitalTrade Player Guide

CapitalTrade adds **Ports**, where you sell everyday goods to faraway lands and, on some servers, buy items your world does not have. This guide explains how it works. Everything here is also available in game with `/trade help`.

Figures such as quotas and prices are the defaults. Your server's owner may have changed them, so the numbers you see in game take priority over those written here.

## Contents
1. [The basics](#the-basics)
2. [Finding a Port](#finding-a-port)
3. [Selling to faraway lands](#selling-to-faraway-lands)
4. [How prices work](#how-prices-work)
5. [Buying from abroad](#buying-from-abroad)
6. [Port News](#port-news)
7. [Your Cargo](#your-cargo)
8. [Playing on Bedrock](#playing-on-bedrock)
9. [Command reference](#command-reference)
10. [Questions and problems](#questions-and-problems)

## The basics
- Ships at the Port buy everyday goods such as crops, logs, ores and common mob drops. The items are shipped away and the coins are yours.
- The ships take only so much of each item every day, and they pay less than other players usually do. Selling to other players is often the better deal; the Port is always there when nobody else is buying.
- Every sale and purchase is confirmed before it happens. Your balance never goes below zero.

## Finding a Port
A Port is a barrel with a floating **⚓ Port** sign above it, or a Port keeper (a character staff have made into a Port). Right-click it to open the Port menu, or type `/trade` within 8 blocks of one. Ask staff where the nearest Port is.

The Port menu has these buttons:

| Button | What it does |
|---|---|
| **Sell to Faraway Lands** | Lists every item the ships buy, with today's price. |
| **Buy from Abroad** | Lists items brought in from abroad. Only shown if your server has any. |
| **Port News** | What faraway lands want this week, and today's busiest exports. |
| **Collect Cargo** | Gives you any items waiting for you at the Port. |
| **Help** | Shows the help topics in chat. |

## Selling to faraway lands
1. Open **Sell to Faraway Lands** and pick an item. Each item shows its price today and how many more the ships can take today, for example "Ships can take 340 more today".
2. Choose **Sell 1**, **Sell 16**, **Sell 64**, **Sell all you hold**, or **Choose an amount**.
3. A confirmation shows how many will be sold and exactly what you will receive. Choose **Yes**.

You can also hold the item and type `/trade sell`, `/trade sell 32` or `/trade sell all`, then `/trade yes` to confirm.

**Daily limits.** The ships take a set amount of each item every day, and more when more players are active that day. They also have a limited number of coins to spend each day. If you offer more than they can take, they buy what they can and the rest comes straight back to you. Once they are full, try again tomorrow or sell to other players.

Only plain items are accepted: items with custom names or lore, and damaged tools or armour, are not.

## How prices work
- Every item has a **world price** that moves a little each day, shown with ▲ or ▼ and how much it changed. It never falls below half or rises above twice its usual price.
- **Prices fall as the ships fill up.** The first items of the day get the full price; by the time the day's quota is full, the price is half. The menu shows what the next one pays.
- Selling one stack at a time pays exactly the same in total as selling everything at once.

## Buying from abroad
Some servers bring in items their world does not have. If yours does, the Port menu has a **Buy from Abroad** button.
- The price includes an **import tax**. The tax goes to the server; the rest is paid to faraway lands.
- Only a few of each item arrive every day, and the price rises as more are bought that day.
- Pick an item, choose an amount, and confirm. The confirmation shows the full price and the tax. Items go into your inventory, or into your Cargo if it is full.

Command: `/trade buy <item> <amount>`, then `/trade yes`.

## Port News
Some weeks, faraway lands want one item more and pay extra for it, for example *"⚓ Port News: Faraway lands want PUMPKINS this week! Prices are up 30% at the Port."* The news is announced in chat when it starts. Check it any time with the **Port News** button or `/trade news`.

## Your Cargo
Items that do not fit in your inventory wait in your **Cargo**: things you bought from abroad, and anything the ships could not take. Nothing in your Cargo is ever lost. Collect it at any Port with **Collect Cargo** or `/trade cargo`.

## Playing on Bedrock
Everything works on Bedrock through Geyser:
- The menus are ordinary chest menus.
- **Choose an amount** opens a form on servers with Floodgate. Without it, you are asked in chat, and you can type `cancel` to stop.
- Confirmations from commands appear as a Yes/No form. If none appears, type `/trade yes` within 30 seconds.
- Prices are shown in item names and in chat as well as in the item description, since descriptions do not always show on Bedrock.

## Command reference
| Command | What it does |
|---|---|
| `/trade` | Opens the Port menu (at a Port). |
| `/trade help [topic]` | Explains how Ports work. Topics: start, export, import, prices, news, cargo, commands. |
| `/trade sell [amount\|all]` | Sells the item in your hand to faraway lands, after confirmation. |
| `/trade buy <item> <amount>` | Buys an item from abroad, after confirmation. |
| `/trade cargo` | Collects items waiting for you (at a Port). |
| `/trade news` | Shows Port News. |
| `/trade yes` | Confirms the last sale or purchase. |

## Questions and problems
- **"You need to be at a Port."** Walk to a Port; `/trade` works within 8 blocks of one.
- **"The ships are full for today!"** The ships have taken all they can of that item, or spent their coins for the day. Try again tomorrow, or sell to other players.
- **"Faraway lands do not buy this."** Only the items listed under **Sell to Faraway Lands** can be exported.
- **My items did not come back.** Check your Cargo with `/trade cargo` at any Port. If they are not there either, ask staff.
