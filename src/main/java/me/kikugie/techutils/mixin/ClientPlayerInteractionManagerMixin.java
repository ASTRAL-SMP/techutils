package me.kikugie.techutils.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import me.kikugie.techutils.feature.inverifier.ContainerOverlay;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ClientPlayerInteractionManager.class)
public class ClientPlayerInteractionManagerMixin {
    @ModifyReturnValue(method = "interactBlock", at = @At("RETURN"))
    private ActionResult recordContainer(ActionResult result, @Local(argsOnly = true) BlockHitResult hitResult) {
        if (result.isAccepted()) {
            ContainerOverlay.onContainerClick(hitResult);
        }
        return result;
    }
}
