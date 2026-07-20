package me.kikugie.techutils.feature.verifier;

import com.chocohead.mm.api.ClassTinkerers;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;

import java.util.List;

/**
 * Implemented (via mixin) by Litematica's {@link SchematicVerifier} to expose the wrong-inventory
 * mismatches this mod records on top of the vanilla block-state verification.
 */
public interface SchematicVerifierExtension {
    MismatchType WRONG_INVENTORIES = ClassTinkerers.getEnum(MismatchType.class, "WRONG_INVENTORIES");

    List<SchematicVerifier.BlockMismatch> getSelectedInventoryMismatches$techutils();

    int getWrongInventoriesCount$techutils();

    /**
     * On a server, queries the real contents of the schematic containers seen during verification
     * and records the ones that don't match. No-op in singleplayer.
     */
    void runQueryPass$techutils();
}
