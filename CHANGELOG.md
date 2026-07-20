# 0.3.0-astral.1 (1.19.4)

ASTRAL-SMP fork bringing the mod to Minecraft 1.19.4 and backporting features from the upstream 0.6.x line that don't rely on the 1.20.5+ data component system.

## Toolchain
- Retargeted from 1.19.3 to **1.19.4** (Yarn 1.19.4+build.2, Loader 0.16.10, Gradle 8.10.2, Loom 1.7.4).
- Dependencies: Fabric API 0.87.2, MaliLib 0.15.4, Litematica 0.14.7, Mod Menu 6.3.1.

## Backported features
- **Inventory Verifier** — adds a "Wrong Inventories" category to Litematica's Schematic Verifier that lists containers whose contents don't match the schematic and renders the expected vs found inventory side by side (`verifyItemNbt` option). The new mismatch type is added to Litematica's enums at runtime via Fabric ASM. Ported from the 1.20.1 branch to NBT; the item-component and Servux-bulk-request paths are dropped, so found contents come from whatever the client already has (singleplayer, opened containers, NBT query permissions, Servux).
- **giveFullInv** — hotkey to give yourself a container full of the held item. Supports shulker boxes, chests and bundles; hold a container in the off-hand to pick the outer container or nest them. Reimplemented on NBT (`bundleFill`, `fillSafety` options).
- **refreshMaterialList** — hotkey to refresh the active Litematica material list.
- **easyPlaceFullBlocks** — make Litematica easy place treat all blocks as full cubes, for placing small-hitbox blocks like buttons, chains and fences.

## Not backported
Item component verification (`verifyItemComponents`) and item predicates depend on the 1.20.5+ data component system, which does not exist in 1.19.4.
