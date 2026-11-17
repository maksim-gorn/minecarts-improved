package com.minecartsupdated.mixin;

import com.minecartsupdated.TrainData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
abstract class EntityRemovalMixin {
	@Inject(method = "setRemoved", at = @At("HEAD"))
	private void minecartsUpdated$dropBrokenCartLinks(Entity.RemovalReason reason, CallbackInfo info) {
		Entity entity = (Entity) (Object) this;
		if (reason.shouldDestroy() && entity instanceof AbstractMinecart cart && cart.level() instanceof ServerLevel level) {
			int links = TrainData.unlinkDestroyedCart(level, cart.getUUID());
			for (int i = 0; i < links; i++) cart.spawnAtLocation(level, Items.IRON_CHAIN);
		}
	}
}
