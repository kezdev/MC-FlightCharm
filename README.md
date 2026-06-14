# FlightCharm

A Paper plugin for Minecraft **26.1+**. A consumable **Flight Charm** that grants
timed creative-style flight, shown with a draining **boss bar** timer. Includes a
custom item texture via a small resource pack.

By [kezdev](https://github.com/kezdev).

## What it does

- Right-click a Flight Charm to gain flight. One charm = 10 minutes by default.
- A boss bar at the top drains over the duration, showing `✈ Flight  9:59`.
- Using more charms stacks time (up to a configurable cap).
- When time runs out, flight ends and you get a few seconds of Slow Falling for a soft landing.
- Remaining time is saved per player, so it survives logout and restarts.
- Bare-hand and creative flight are untouched; charms do nothing useful in creative/spectator.

## Install (plugin)

1. Build the jar (see below) or download `FlightCharm-x.x.x.jar`.
2. Put it in your server's `plugins/` folder.
3. Restart. A `config.yml` is generated under `plugins/FlightCharm/`.

## Custom texture (optional)

**Out of the box the charm looks like a named Feather** — it works everywhere with
no setup and no resource pack. The custom aqua texture is opt-in.

To enable it, the plugin offers the pack to players itself (no need to touch
`server.properties`, and it stacks alongside any other packs):

1. Commit `FlightCharm-ResourcePack.zip` to your repo, then point the config at its
   **raw** URL:

   ```yaml
   resource-pack:
     enabled: true
     url: "https://raw.githubusercontent.com/kezdev/MC-FlightCharm/main/FlightCharm-ResourcePack.zip"
     sha1: "05eb481dc69c46a5a60957c150567da675691281"
     required: false
     prompt: "Install the FlightCharm pack to see the charm's custom texture."
   ```
2. `/flightcharm reload` (or restart). Players are offered the pack on join.

(Prefer immutable URLs? Upload the zip to a **GitHub Release** and use the asset URL
`https://github.com/<you>/FlightCharm/releases/download/<tag>/FlightCharm-ResourcePack.zip`
instead — handy if you want each plugin version pinned to a specific pack.)

**Notes**
- When `enabled` is `true`, the charm carries the custom item-model. Players who
  accept the pack see the texture; players who decline see missing-texture, so use
  `required: true` if the look matters to you.
- A raw-on-`main` URL is mutable: if you change the pack, recompute the SHA-1
  (`sha1sum FlightCharm-ResourcePack.zip`) and update the config, or clients reject the
  download. (`raw.githubusercontent.com` also caches for ~5 min after a push.)
- For solo testing you can instead drop the zip into `.minecraft/resourcepacks/` and
  enable it in *Options → Resource Packs*.

## Commands & Permissions

| Command | Description | Permission |
|---------|-------------|------------|
| `/flightcharm give [player] [amount]` | Give Flight Charm(s) | `flightcharm.give` (op) |
| `/flightcharm time` | Show your remaining flight time | — |
| `/flightcharm reload` | Reload `config.yml` | `flightcharm.reload` (op) |

Alias: `/fc`. (Players obtain charms via `/flightcharm give` for now — add a crafting
recipe later if you want survival acquisition.)

## Configuration

`plugins/FlightCharm/config.yml`:

```yaml
flight-seconds: 600     # how long one charm grants (600 = 10 minutes)
max-seconds: 3600       # cap on total stored flight time
slow-fall-seconds: 8    # Slow Falling grace when flight ends
flight-particle: CLOUD  # particle ring at your feet while flying; NONE to disable
```

`flight-particle` accepts any Bukkit particle name (e.g. `CLOUD`, `END_ROD`,
`HAPPY_VILLAGER`, `SOUL_FIRE_FLAME`). Set it to `NONE` to turn the effect off.

Run `/flightcharm reload` after editing.

## Building

Requires JDK 25. In IntelliJ, run the Gradle **`deployToServer`** task — it builds the
jar and copies it into your server's `plugins/` folder. Override the target with
`-PserverPluginsDir=/path/to/plugins`.

## How the texture works (tech note)

Built for the 1.21.4+ item-model system:

```
resourcepack/
├─ pack.mcmeta                                   # pack_format 84 (Minecraft 26.1)
└─ assets/flightcharm/
   ├─ items/flight_charm.json                    # item-model definition
   ├─ models/item/flight_charm.json              # generated model -> texture
   └─ textures/item/flight_charm.png             # 16×16 texture
```

The plugin sets the item's `minecraft:item_model` component to `flightcharm:flight_charm`
via `ItemMeta#setItemModel(...)`, which selects that model.

## License

MIT © 2026 kezdev.
