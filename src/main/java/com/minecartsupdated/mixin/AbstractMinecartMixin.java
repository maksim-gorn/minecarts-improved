package com.minecartsupdated.mixin;

import com.minecartsupdated.TrainData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractMinecart.class)
abstract class AbstractMinecartMixin {
	@Inject(method = "pushOtherMinecart", at = @At("HEAD"), cancellable = true)
	private void minecartsUpdated$preventTrainSelfPush(AbstractMinecart other, double xa, double za, CallbackInfo info) {
		AbstractMinecart cart = (AbstractMinecart) (Object) this;
		if (cart.level() instanceof ServerLevel level && TrainData.sameTrain(level, cart.getUUID(), other.getUUID())) info.cancel();
	}
}
