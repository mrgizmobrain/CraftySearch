# Crafty Search

A Fabric mod for Minecraft 26.2. Search, count, take and craft from the items in your nearby storage, straight from the inventory and the crafting table, without opening chests by hand.

It works with only the client installed, on any server. With the mod on the server too, or in singleplayer, it is instant and reaches every container in range.

## What it does

- **Storage panel.** A panel beside the inventory and the crafting table lists every item in nearby containers with its total. It has a search box and a sort button, and a key (G by default) opens it as a full screen from anywhere.
- **Finding things.** Ask where an item is and the containers holding it are outlined through walls, each with how many it holds. Open one and the slots with the item are marked.
- **Crafting from storage.** The vanilla recipe book counts what is in storage. Click a recipe and the ingredients are pulled out and crafted. Missing ingredients can be crafted first, such as planks from logs.
- **Taking and putting away.** Click a tile to take a stack; drop a stack on the panel to store it. Items are stored the way a copper golem sorts them: into a container that already holds that item.
- **Containers.** Chests, barrels, shulker boxes, hoppers, droppers, dispensers, chest and hopper minecarts, and chest boats. What is inside shulker boxes is counted too.

## Two modes

| | Server mode | Client-only mode |
|---|---|---|
| When | The server has the mod, or singleplayer | The server doesn't have the mod |
| What it knows | The live contents of every container in range | The containers you have opened, which it remembers |
| Reach | The whole radius (32 blocks by default) | Taking and crafting only from containers within reach |
| How items move | The server moves them directly | Containers are opened in the background and clicked as a player would |

In server mode the mod follows claims (Open Parties and Claims, Flan, and mods using the Common Protection API), spawn protection and container locks, so nobody reaches storage they couldn't open by hand. Players without the mod can still join a server that has it.

In client-only mode the panel only knows what it saw when you last opened a container, so on a shared server it can be out of date. When it sees someone else open a chest, barrel or shulker box, it marks that item's tile with an orange corner until the container is read again. "Show where" outlines that container in orange, and "Open" goes to it first. Shift-click the sort button to check every container within reach at once. That includes ones you haven't opened, and carts, hoppers and droppers, which give no sign when others use them.

In client-only mode the server sees ordinary container clicks, made for you in the background. Check a server's rules before using it there. The "Client-only mode" settings page has a paced Normal speed, and a Fast speed for servers without anti-cheat. "Anti-cheat safe" on that page holds every option an anti-cheat may flag at its cautious value with one switch.

## Controls

On a tile in the panel:

| Input | What it does |
|---|---|
| Click | Take a stack onto the cursor |
| Shift-click | Take a stack straight into the inventory |
| Right-click | Open the nearest container holding it |
| Shift-right-click | Take all that fits |
| Middle-click or Alt-click | Show where it is |
| Scroll | Take one, or put one away, per notch |
| Ctrl+scroll | A stack per notch |
| Shift+scroll | Scroll the panel |
| Q / Ctrl+Q (the drop key) | Throw one, or a stack, straight from storage |
| Shift-drag over tiles | Take a stack of each |

Right-click and Shift-right-click can each be set to open the container, take one, show where, or take all that fits.

In the inventory, while the panel shows:

| Input | What it does |
|---|---|
| Shift-click a stack | Put it away |
| Shift-double-click a stack | Put away every stack of that item |
| Shift-drag over stacks | Put each away |
| Scroll over a stack | Take more of it, or put some away |
| Click the panel holding a stack | Put it away (right-click: just one) |

Hold Ctrl over a tile to see its controls in the tooltip.

**Search.** Plain text matches item names and enchantment names. `#tag` matches an item tag, `@mod` a mod, and a number after a word an enchantment level, as in `sharpness 5`.

**Recipe book.** An orange tint means the ingredients are in storage out of reach (client-only mode). A blue tint means the recipe is craftable by crafting other items first.

## Settings

Open them from Mod Menu, or with the Settings button on the storage screen. They cover the radius, how long highlights last, where new items are put away, which containers count, the mouse controls, and the client-only options. Two worth knowing:

- **Leave one** keeps one of each item in every container, so put-away and copper golems keep sending that item there.
- **Only takeable** shows only what you could take right now.

A server's settings are in `config/craftysearch-server.json`:

| Setting | Default | What it does |
|---|---|---|
| `enabled` | `true` | Off, clients use client-only mode |
| `maxRadius` | `32` | The largest radius a client may ask for |
| `allowCrafting` | `true` | Crafting from storage |
| `allowTakeAndPutAway` | `true` | Taking and putting away from the panel |
| `allowRemoteOpen` | `true` | Opening a container from the panel from anywhere in range |
| `respectContainerLocks` | `true` | Locked containers are left alone |
| `respectClaims` | `true` | Containers in other players' claims and inside spawn protection are left alone |

## Works with

- [Mod Menu](https://modrinth.com/mod/modmenu): opens the settings.
- [JEI](https://modrinth.com/mod/jei) and [REI](https://modrinth.com/mod/rei): their item lists keep clear of the panel.
- [Mouse Tweaks](https://modrinth.com/mod/mouse-tweaks): its scrolling and dragging work alongside the panel's.
- [Inventory Profiles Next](https://modrinth.com/mod/inventory-profiles-next): locked slots are never put away or filled.
- Shader packs through [Iris](https://modrinth.com/mod/iris): highlights show as markers on screen, since shaders hide the outlines behind walls.

## Installing

Crafty Search needs Minecraft 26.2, Fabric Loader 0.19.3 or newer, [Fabric API](https://modrinth.com/mod/fabric-api) and Java 25. Put the jar in your `mods` folder. Putting the same jar on a server is optional.

## Building

With JDK 25:

```
./gradlew build
```

The jar is written to `build/libs`. `./gradlew runClient` starts a development client; add `-Pwith=jei` (or `rei`, `opac`, `flan`, `cpa`, comma-separated) to try an integration.

The design, and the decisions made along the way, are in [SPEC.md](SPEC.md).

## License

[MIT](LICENSE)
