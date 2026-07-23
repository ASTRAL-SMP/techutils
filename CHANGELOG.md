# 0.3.2-astral (1.19.4)

- Inventory Verifier: the preview now uses the container's own layout, so a hopper is drawn as a row of five slots instead of a single column. The layout is taken from the block, because the copied contents could only be classified as a horse inventory.
- Inventory Verifier: the listed containers are re-read when the verifier screen is opened, so filling one clears its entry instead of keeping it until the whole verification is run again.

# 0.3.1-astral (1.19.4)

- Inventory Verifier: the hovered entry's inventories are now shown while entries are selected. Previously selecting an entry pinned its overlay and hovering another one did nothing; the selection is still shown when the cursor is off the list.

# 0.3.0-astral.1 (1.19.4)

ASTRAL-SMP fork bringing the mod to Minecraft 1.19.4 and backporting features from the upstream 0.6.x line that don't rely on the 1.20.5+ data component system.

## Toolchain
- Retargeted from 1.19.3 to **1.19.4** (Yarn 1.19.4+build.2, Loader 0.16.10, Gradle 8.10.2, Loom 1.7.4).
- Dependencies: Fabric API 0.87.2, MaliLib 0.15.4, Litematica 0.14.7, Mod Menu 6.3.1.

## Backported features
- **Inventory Verifier** — adds a "Wrong Inventories" category to Litematica's Schematic Verifier that lists containers whose contents don't match the schematic and renders the expected vs found inventory side by side, with an item tooltip on hover (`verifyItemNbt` option). The new mismatch type is added to Litematica's enums at runtime via Fabric ASM. Ported from the 1.20.1 branch to NBT (item components dropped).
  - On a server the client doesn't have container contents, so after verification the real contents are fetched with rate-limited block NBT queries (`packetRate` / `packetTimeout` / `queryTimeout`). This needs the block-query permission (op / a permissions mod); in singleplayer contents are read directly.
- **giveFullInv** — hotkey to give yourself a container full of the held item. Supports shulker boxes, chests and bundles; hold a container in the off-hand to pick the outer container or nest them. Reimplemented on NBT (`bundleFill`, `fillSafety` options).
- **refreshMaterialList** — hotkey to refresh the active Litematica material list.
- **easyPlaceFullBlocks** — make Litematica easy place treat all blocks as full cubes, for placing small-hitbox blocks like buttons, chains and fences.

## Not backported
Item component verification (`verifyItemComponents`) and item predicates depend on the 1.20.5+ data component system, which does not exist in 1.19.4.
