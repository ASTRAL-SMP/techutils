package me.kikugie.techutils;

import com.chocohead.mm.api.ClassTinkerers;

/**
 * Runs before Litematica's classes are loaded and adds a new {@code WRONG_INVENTORIES} value to the
 * Schematic Verifier's mismatch-type and button-type enums, so the verifier can report containers
 * whose contents don't match the schematic.
 */
public class EarlyRiser implements Runnable {
    @Override
    public void run() {
        String mismatchType = "fi.dy.masa.litematica.schematic.verifier.SchematicVerifier$MismatchType";
        ClassTinkerers.enumBuilder(mismatchType, int.class, String.class, String.class)
                .addEnum("WRONG_INVENTORIES", 0xFF0000, "litematica.gui.label.schematic_verifier_display_type.wrong_inventories", "§4")
                .build();

        String buttonListenerType = "fi.dy.masa.litematica.gui.GuiSchematicVerifier$ButtonListener$Type";
        ClassTinkerers.enumBuilder(buttonListenerType)
                .addEnum("SET_RESULT_MODE_WRONG_INVENTORIES")
                .build();
    }
}
