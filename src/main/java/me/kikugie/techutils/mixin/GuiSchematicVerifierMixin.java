package me.kikugie.techutils.mixin;

import com.chocohead.mm.api.ClassTinkerers;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import me.kikugie.techutils.feature.verifier.SchematicVerifierExtension;
import fi.dy.masa.litematica.gui.GuiSchematicVerifier;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.malilib.util.GuiUtils;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Adds the "Wrong Inventories" filter button to Litematica's Schematic Verifier screen. The new enum
 * constant is added at runtime (see EarlyRiser), so it can only be referenced reflectively here.
 */
@Mixin(value = GuiSchematicVerifier.class, remap = false)
public abstract class GuiSchematicVerifierMixin {
    @Shadow private static SchematicVerifier.MismatchType resultMode;
    @Shadow @Final private SchematicVerifier verifier;

    @Unique
    private static final Enum<?> SET_RESULT_MODE_WRONG_INVENTORIES;
    @Unique
    private static final Method CREATE_BUTTON;

    static {
        try {
            Class<?> typeClass = Class.forName("fi.dy.masa.litematica.gui.GuiSchematicVerifier$ButtonListener$Type");
            //noinspection unchecked,rawtypes
            SET_RESULT_MODE_WRONG_INVENTORIES = ClassTinkerers.getEnum(
                    (Class<? extends Enum>) typeClass,
                    "SET_RESULT_MODE_WRONG_INVENTORIES"
            );
            CREATE_BUTTON = GuiSchematicVerifier.class.getDeclaredMethod("createButton", int.class, int.class, int.class, typeClass);
            CREATE_BUTTON.setAccessible(true);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    @Inject(
            method = "initGui",
            slice = @Slice(
                    from = @At(
                            value = "FIELD",
                            target = "Lfi/dy/masa/litematica/gui/GuiSchematicVerifier$ButtonListener$Type;SET_RESULT_MODE_ALL:Lfi/dy/masa/litematica/gui/GuiSchematicVerifier$ButtonListener$Type;"
                    )
            ),
            at = @At(
                    value = "INVOKE",
                    target = "Lfi/dy/masa/litematica/gui/GuiSchematicVerifier;createButton(IIILfi/dy/masa/litematica/gui/GuiSchematicVerifier$ButtonListener$Type;)I",
                    ordinal = 0,
                    shift = At.Shift.BY,
                    by = 5
            )
    )
    private void techutils$addWrongInventoriesButton(CallbackInfo ci, @Local(ordinal = 0) LocalIntRef x, @Local(ordinal = 1) int y) throws InvocationTargetException, IllegalAccessException {
        var res = (Integer) CREATE_BUTTON.invoke(this, x.get(), y, -1, SET_RESULT_MODE_WRONG_INVENTORIES);
        x.set(x.get() + res + 4);
    }

    /**
     * Container contents are only read while a chunk is verified, so a container filled afterwards
     * stays listed. Opening the screen re-reads the listed ones and rebuilds the list if any of them
     * turned out to be correct by now.
     */
    @Inject(method = "initGui", at = @At("HEAD"))
    private void techutils$recheckWrongInventories(CallbackInfo ci) {
        GuiSchematicVerifier self = (GuiSchematicVerifier) (Object) this;
        ((SchematicVerifierExtension) verifier).recheckWrongInventories$techutils(() -> {
            if (GuiUtils.getCurrentScreen() == self) {
                self.onTaskCompleted();
            }
        });
    }

    @ModifyExpressionValue(method = "createButton", at = @At(value = "INVOKE", target = "Lfi/dy/masa/litematica/gui/GuiSchematicVerifier$ButtonListener$Type;ordinal()I", ordinal = 0))
    private int techutils$labelWrongInventoriesButton(int ordinal, @Local LocalBooleanRef enabled, @Local LocalRef<String> label) {
        if (ordinal == SET_RESULT_MODE_WRONG_INVENTORIES.ordinal()) {
            label.set(SchematicVerifierExtension.WRONG_INVENTORIES.getDisplayname());
            enabled.set(resultMode != SchematicVerifierExtension.WRONG_INVENTORIES);
        }
        return ordinal;
    }

    @Mixin(targets = "fi.dy.masa.litematica.gui.GuiSchematicVerifier$ButtonListener", remap = false)
    public static class ButtonListenerMixin {
        @Unique
        private static final Enum<?> SET_RESULT_MODE_WRONG_INVENTORIES;
        @Unique
        private static final Method SET_RESULT_MODE;

        static {
            try {
                Class<?> typeClass = Class.forName("fi.dy.masa.litematica.gui.GuiSchematicVerifier$ButtonListener$Type");
                //noinspection unchecked,rawtypes
                SET_RESULT_MODE_WRONG_INVENTORIES = ClassTinkerers.getEnum(
                        (Class<? extends Enum>) typeClass,
                        "SET_RESULT_MODE_WRONG_INVENTORIES"
                );
                SET_RESULT_MODE = GuiSchematicVerifier.class.getDeclaredMethod("setResultMode", SchematicVerifier.MismatchType.class);
                SET_RESULT_MODE.setAccessible(true);
            } catch (ClassNotFoundException | NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
        }

        @Shadow @Final private GuiSchematicVerifier parent;

        @ModifyExpressionValue(method = "actionPerformedWithButton", at = @At(value = "INVOKE", target = "Lfi/dy/masa/litematica/gui/GuiSchematicVerifier$ButtonListener$Type;ordinal()I", ordinal = 0))
        private int techutils$handleWrongInventoriesButton(int ordinal) throws InvocationTargetException, IllegalAccessException {
            if (ordinal == SET_RESULT_MODE_WRONG_INVENTORIES.ordinal()) {
                SET_RESULT_MODE.invoke(parent, SchematicVerifierExtension.WRONG_INVENTORIES);
            }
            return ordinal;
        }
    }
}
