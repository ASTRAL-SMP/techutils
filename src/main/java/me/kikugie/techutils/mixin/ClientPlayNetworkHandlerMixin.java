package me.kikugie.techutils.mixin;

import com.mojang.brigadier.ParseResults;
import me.kikugie.techutils.networking.GamerQueryHandler;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.command.CommandSource;
import net.minecraft.network.packet.s2c.play.NbtQueryResponseS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
    @Shadow protected abstract ParseResults<CommandSource> parse(String command);

    @Inject(method = "onNbtQueryResponse", at = @At("HEAD"))
    private void onGamerQueryResponse(NbtQueryResponseS2CPacket packet, CallbackInfo ci) {
        if (packet.getTransactionId() < 0) {
            GamerQueryHandler.INSTANCE.handleQueryResponse(packet.getTransactionId(), packet.getNbt());
        }
    }
}
