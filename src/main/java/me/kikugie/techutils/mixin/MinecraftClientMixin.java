package me.kikugie.techutils.mixin;

import me.kikugie.techutils.feature.inverifier.ContainerOverlay;
import me.kikugie.techutils.render.TransparencyBuffer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Environment(EnvType.CLIENT)
@Mixin(value = MinecraftClient.class)
public class MinecraftClientMixin {
    @Inject(method = "onResolutionChanged", at = @At("RETURN"))
    private void resizeDisplay(CallbackInfo ci) {
        TransparencyBuffer.resizeDisplay();
    }

    // Bound here rather than in HandledScreen#init, which also runs on every window resize.
    @Inject(method = "setScreen", at = @At("HEAD"))
    private void bindContainerOverlay(@Nullable Screen screen, CallbackInfo ci) {
        if (screen instanceof HandledScreen<?>) {
            ContainerOverlay.onScreenOpened();
        }
    }
}
