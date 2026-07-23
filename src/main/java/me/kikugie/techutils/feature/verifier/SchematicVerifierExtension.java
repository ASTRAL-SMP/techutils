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
     * Re-reads the real contents of the containers currently listed as wrong and drops the ones that
     * now match the schematic. The contents are only read once while a chunk is verified, so without
     * this a container stays listed after being filled. Fetching them is asynchronous on a server,
     * so {@code onChanged} runs later on the client thread, and only if the list actually changed.
     */
    void recheckWrongInventories$techutils(Runnable onChanged);
}
