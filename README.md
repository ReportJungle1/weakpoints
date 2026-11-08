# Weak Points

Gathering in the style of Rust for Fabric 1.21.1. Click an ore or a tree trunk and a glowing point appears on it. Hit the point a few times and the whole ore vein or the whole tree comes out at once.

## How it works

- **First click.** Left-click a natural ore with a pickaxe that can mine it, or a tree trunk with an axe or a bare hand. A glowing star appears on the block, and an outline shows what will come out.
- **Blows.** Each hit on the star counts as a blow, and then the star jumps to another spot. You get one blow per swing of your tool, and missing the star does nothing. A short toast tells you how many blows you need the first time.
- **How many blows.**
  - Wood or stone tool: 5
  - Iron: 4
  - Diamond: 3
  - Netherite or gold: 2
  - A tree by hand: 12
- **The last blow.** Once the last blow lands, the vein comes out or the tree comes down log by log from the bottom up. The leaves follow shortly after.

## What counts

- **Ore veins.**
  - Ore blocks connected to each other are one vein, diagonals included.
  - Ores of the same kind count together, so iron and deepslate iron in one vein come out together.
  - A vein takes up to 32 blocks.
  - The outline shows only ore you can already see, so it can't be used as x-ray. The buried part of the vein still comes out with the rest.
- **Trees.**
  - Logs connected to each other are one tree, up to 128 logs.
  - Logs count as a tree only if natural leaves grow on them. That covers trees grown from saplings and nether fungi, but not village houses.
  - Once the trunk is gone, its leaves fall the way they would decay, with saplings and apples. Leaves still held by another tree stay.
- **Placed blocks never count.** Logs and ores you placed yourself break the ordinary way, so your log walls and ore displays are safe.

## Fair play

- **Like normal mining.** Every block taken counts as mined by you:
  - Fortune and Silk Touch apply.
  - Experience drops.
  - Your tool wears by one point per block.
  - You get hungry as if you had mined it.
  - Mining statistics go up.
- **Drops.** Everything drops at the spot you hit, not scattered across the vein.
- **Broken tool.** If your tool breaks partway through, the rest of the vein stays where it is.
- **No mining around it.** Natural ore that your pickaxe could take whole can only be taken through its weak points. Holding the button on it does nothing. A pickaxe that gets nothing from the ore can still dig through it. Logs can still be chopped one at a time.
- **Creative and spectator.** There are no weak points in Creative or Spectator mode.

## Game rules

| Rule | Default | Meaning |
|---|---|---|
| `weakPoints` | `true` | Turns the whole feature on or off |
| `weakPointsVeinLimit` | `32` | Most ore blocks taken in one vein (1–512) |
| `weakPointsTreeLimit` | `128` | Most logs taken in one tree (1–1024) |

## Installation

- **Requirements.** Fabric Loader 0.15.11 or newer and Fabric API, on Minecraft 1.21.1.
- **Singleplayer.** Put the mod in your `mods` folder.
- **Multiplayer.**
  - Install it on the server and on each client.
  - Players who join without the mod see no weak points and mine as in vanilla.
- **Mod Menu.** The description is shown in English and Russian.

## Building

The project needs JDK 21 for the mod itself and JDK 25 to run Gradle, because Loom 1.19 requires it.

```
./gradlew build          # the mod jar lands in build/libs
./gradlew test           # unit tests
./gradlew runGametest    # in-game tests on a headless server
```

## License

LGPL-3.0-or-later. See `COPYING` and `COPYING.LESSER`.
