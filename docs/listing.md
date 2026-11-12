# Listing for Modrinth and CurseForge

What to fill in on each site. The icon is `src/main/resources/assets/craftysearch/icon.png` (512×512; `tools/Icon.java` draws it). The jar is `build/libs/craftysearch-1.0.0.jar`.

## Fields

| Field | Value |
|---|---|
| Name | Crafty Search |
| Summary | Search, count, take and craft from your nearby storage, right from the inventory. Works on any server. |
| License | MIT |
| Source | https://github.com/mrgizmobrain/CraftySearch |
| Issues | https://github.com/mrgizmobrain/CraftySearch/issues |
| Loader | Fabric |
| Game version | 26.2 |
| Java | 25 |
| Environment | Client: required. Server: optional. |
| Dependencies | Fabric API (required), Mod Menu (optional) |
| Categories | Storage, Utility (Modrinth: also Management) |
| Release type | Release |
| Changelog | The 1.0.0 section of `CHANGELOG.md` |

## Description

Copy from "**Crafty Search** lets you..." down to the end of the file.

**Crafty Search** lets you search, count, take and craft from the items in your nearby storage, straight from the inventory and the crafting table, without opening chests one by one.

It works with only the client installed, on any server. Put it on the server too (or play singleplayer) and it's instant and reaches every container in range.

### Features

- **Storage panel.** A panel beside your inventory and crafting table lists every item in nearby containers with its total. Search by name, by enchantment (`sharpness 5`), by tag (`#logs`) or by mod (`@minecraft`), and sort by count, name or mod. Press G to open it full screen from anywhere.
- **Find things.** Ask where an item is and the containers holding it light up through walls, each with how many it holds. Open one and the slots with the item are marked. Works with shader packs too.
- **Craft from storage.** The recipe book counts what's in your storage. Click a recipe and the ingredients are pulled out and crafted, and missing ingredients (planks from logs, sticks from planks) are crafted first.
- **Take and put away.** Click a tile to take a stack, scroll to take or store one at a time, shift-click a stack in your inventory to store it, shift-drag over many at once. Items go into a container that already holds them, the way a copper golem sorts.
- **Containers.** Chests, barrels, shulker boxes (their contents count too), hoppers, droppers, dispensers, chest and hopper minecarts, and chest boats.
- **Leave one.** Optionally keeps one of each item in every container, so your sorting keeps working.

### Two modes

| | With the mod on the server | Client only |
|---|---|---|
| What it knows | Everything in range, live | What you've opened, remembered |
| Reach | The whole radius (32 blocks by default) | Taking and crafting from containers within reach |
| How items move | The server moves them | Containers are opened in the background for you |

On a server with the mod, it respects claims (Open Parties and Claims, Flan, and mods using the Common Protection API), spawn protection and container locks.

### On servers without the mod

In client-only mode the server sees ordinary container clicks, made for you in the background. **Check a server's rules before using it there.**

- **Anti-cheat safe** (on by default) keeps everything at a careful pace, with the riskier options off.
- **All interactions** off stops every click, while still remembering what you open and showing where things are.
- On a shared server, containers someone else has opened get an orange corner on their tiles.
- Shift-click the sort button to check every container within reach at once.

### Works with

Made to work with these:

- **Mod Menu:** opens the settings.
- **JEI and REI:** their item lists keep clear of the panel.
- **Mouse Tweaks:** its scrolling and dragging work alongside the panel's.
- **Inventory Profiles Next:** locked slots are never put away or filled.
- **Iris shader packs:** highlights show as markers on screen.

It should get along with most other mods too. If one doesn't, please open an issue.
