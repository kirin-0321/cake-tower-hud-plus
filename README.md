**Cake Tower HUD Plus** is a HUD overhaul and optional **server-side combat analytics** mod built for the community map **Cake Team Towers · Chapter 3**. It turns boss-bar text and `/trigger ViewStats` spam into readable bars, panels, and live team stats—without editing the map or needing OP.
> **Map-specific:** designed for *Cake Team Towers Chapter 3*. On other worlds or servers the UI **stays hidden** automatically.
---
## Features
### Status & hearts
- **Custom health bar** with gradient and low-HP warning (flashes below ~25%).
- **All four heart types** (red / soul / black / blue) stacked and updated live—no wall of `ViewStats` text.
- **Mana, coins, lives, momentum** on separate bars (bottom-right).
- **Class-aware UI** (e.g. vampire blood gauge, jelly dash icon) when you switch class.
### Team & mobs
- **Left roster**: skin, name, HP bar, lives; sorted by lives; **you’re always top row (gold name)**. Draggable; horizontal or vertical layout.
- **3D overhead HP** for teammates (see through walls).
- **3D overhead HP** for every boss-bar mob; **closest target** gets a yellow **▶** marker; enraged/elite suffixes preserved; mob name colors match the map.
### Stats & boss bars
- Long **ViewStats** output becomes a **two-column panel** with tooltips preserved. Modes: always on / only when inventory open / hidden.
- **Independent toggles** for self / party / mob boss bars—**compatible with other boss-bar mods** (still test your pack).
- **Automation**: periodic `ViewStats`, refresh on damage/big heals; “Your Stats:” spam swallowed; optional auto `TogglePartyBossbar` with **circuit breaker** to avoid rate-limit kicks.
### Combat analytics (v6+)
Install on the **server** (integrated singleplayer counts too). Tested on normal dedicated servers.
- **9 damage types** tracked per player with attribution (Melee / Bullet / Force / Fire / Water / Ice / Dark / Light / Electric)—handles teammates, summons, and DoT-style carryover sensibly.
- **Kills / assists / damage taken**; optional chat broadcasts (configurable).
- **Live lines** on the team panel: damage, taken, kills, assists, **rolling 5s DPS**; modes: off / current stage / session / both.
- **Auto-save**: up to **20 past sessions** on disk; periodic + stage transitions + shutdown.
- **L** (bind in controls): detailed damage panel (experimental, **unbound by default**).
- **N**: full-session **K/D/A** table.
### Config & localization
- **ModMenu** (optional but recommended): main HUD screen + **Server config** sub-screen.
- All major HUD panels **draggable**.
- **English** + **简体中文**.
---
## Default keybinds
| Key | Action |
|-----|--------|
| **H** | Toggle entire HUD |
| **J** | Cycle stats panel visibility |
| **K** | Toggle all boss bars |
| **L** | Detailed damage panel *(unbound by default)* |
| **N** | Session K/D/A table |
---
## Requirements
- **Minecraft** `1.21.4`
- **Fabric Loader** `≥ 0.16.9`
- **Java** `21+`
- **Fabric API**
**Analytics:** for full server-side stats, install this mod on the **server** as well as the client. Client-only still gives the HUD on the map.
---
## Safety & compatibility
- **No datapack edits**, no OP, no arbitrary save writes for the map itself; uses the map’s own **`/trigger`** flow.
- **Auto-hides** outside the CTT map context so vanilla survival / unrelated servers stay clean.
---
## Credits
- **Author:** Kirin0321 (麒麟)
- **License:** MIT
