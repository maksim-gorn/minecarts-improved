package com.minecartsupdated;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.Vec3;
import net.minecraft.network.chat.Component;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.UUID;

final class TrainCouplings {
	private static final double MAX_LINK_GAP_SQUARED = 1.0;
	private static final Map<UUID, UUID> PENDING = new java.util.concurrent.ConcurrentHashMap<>();

	static InteractionResult interact(Player player, Level level, InteractionHand hand, Entity target, net.minecraft.world.phys.EntityHitResult hit) {
		if (player.isSpectator() || !(target instanceof AbstractMinecart cart) || level.isClientSide() || !(level instanceof ServerLevel serverLevel)) return InteractionResult.PASS;
		TrainData data = TrainData.get(serverLevel.getServer());
		if (player.getItemInHand(hand).is(Items.SHEARS)) {
			Set<java.util.UUID> links = Set.copyOf(data.neighbors(cart.getUUID()));
			if (links.isEmpty()) return InteractionResult.PASS;
			data.unlinkAll(cart.getUUID());
			for (int i = 0; i < links.size(); i++) cart.spawnAtLocation(serverLevel, Items.IRON_CHAIN);
			player.getItemInHand(hand).hurtAndBreak(1, player, hand.asEquipmentSlot());
			notify(player, "Вагонетка отцеплена.");
			return InteractionResult.SUCCESS;
		}
		if (!player.getItemInHand(hand).is(Items.IRON_CHAIN)) return InteractionResult.PASS;
		if (!onRail(serverLevel, cart.blockPosition())) return reject(player, "Вагонетка должна стоять на рельсах.");
		UUID pending = PENDING.get(player.getUUID());
		if (pending == null) {
			PENDING.put(player.getUUID(), cart.getUUID());
			notify(player, "Цепь выбрана. Нажмите по второй вагонетке.");
			return InteractionResult.SUCCESS;
		}
		if (pending.equals(cart.getUUID())) {
			PENDING.remove(player.getUUID());
			return InteractionResult.SUCCESS;
		}
		AbstractMinecart other = serverLevel.getEntity(pending) instanceof AbstractMinecart selected ? selected : null;
		PENDING.remove(player.getUUID());
		if (other == null || !other.isAlive() || !onRail(serverLevel, other.blockPosition())) return reject(player, "Обе вагонетки должны стоять на рельсах.");
		if (boundaryGapSquared(cart, other) > MAX_LINK_GAP_SQUARED) return reject(player, "Между вагонетками должно быть не больше одного блока.");
		if (!sameRailPath(serverLevel, cart.blockPosition(), other.blockPosition())) return reject(player, "Вагонетки должны быть на одном непрерывном пути.");
		if (data.connected(cart.getUUID(), other.getUUID()) || data.neighbors(cart.getUUID()).size() >= 2 || data.neighbors(other.getUUID()).size() >= 2 || wouldLoop(data, cart.getUUID(), other.getUUID())) return reject(player, "Эти вагонетки нельзя соединить.");
		data.link(cart.getUUID(), other.getUUID());
		if (!player.getAbilities().instabuild) player.getItemInHand(hand).shrink(1);
		notify(player, "Вагонетки сцеплены.");
		return InteractionResult.SUCCESS;
	}

	private static InteractionResult reject(Player player, String message) {
		notify(player, message);
		return InteractionResult.FAIL;
	}

	private static void notify(Player player, String message) {
		if (player instanceof ServerPlayer serverPlayer) serverPlayer.sendOverlayMessage(Component.literal(message));
	}

	private static boolean wouldLoop(TrainData data, java.util.UUID start, java.util.UUID goal) {
		Set<java.util.UUID> visited = new HashSet<>();
		ArrayDeque<java.util.UUID> queue = new ArrayDeque<>();
		queue.add(start);
		while (!queue.isEmpty()) {
			java.util.UUID current = queue.remove();
			if (!visited.add(current)) continue;
			if (current.equals(goal)) return true;
			queue.addAll(data.neighbors(current));
		}
		return false;
	}

	private static boolean onRail(ServerLevel level, BlockPos pos) {
		return BaseRailBlock.isRail(level.getBlockState(pos)) || BaseRailBlock.isRail(level.getBlockState(pos.below()));
	}

	private static double boundaryGapSquared(AbstractMinecart first, AbstractMinecart second) {
		var a = first.getBoundingBox();
		var b = second.getBoundingBox();
		double x = Math.max(0.0, Math.max(a.minX - b.maxX, b.minX - a.maxX));
		double y = Math.max(0.0, Math.max(a.minY - b.maxY, b.minY - a.maxY));
		double z = Math.max(0.0, Math.max(a.minZ - b.maxZ, b.minZ - a.maxZ));
		return x * x + y * y + z * z;
	}

	private static boolean sameRailPath(ServerLevel level, BlockPos first, BlockPos second) {
		BlockPos a = railPos(level, first), b = railPos(level, second);
		if (a == null || b == null) return false;
		Set<BlockPos> seen = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		queue.add(a);
		while (!queue.isEmpty()) {
			BlockPos pos = queue.remove();
			if (!seen.add(pos)) continue;
			if (pos.equals(b)) return true;
			if (Math.abs(pos.getX() - a.getX()) + Math.abs(pos.getY() - a.getY()) + Math.abs(pos.getZ() - a.getZ()) >= 4) continue;
			BlockState state = level.getBlockState(pos);
			RailShape shape = state.getValue(((BaseRailBlock) state.getBlock()).getShapeProperty());
			for (Direction direction : exits(shape)) {
				BlockPos next = pos.relative(direction);
				BlockPos nextRail = railPos(level, next);
				if (nextRail != null && level.getChunkSource().hasChunk(nextRail.getX() >> 4, nextRail.getZ() >> 4)) queue.add(nextRail);
			}
		}
		return false;
	}

	private static Direction[] exits(RailShape shape) {
		return switch (shape) {
			case NORTH_SOUTH -> new Direction[]{Direction.NORTH, Direction.SOUTH};
			case EAST_WEST -> new Direction[]{Direction.EAST, Direction.WEST};
			case ASCENDING_EAST -> new Direction[]{Direction.WEST, Direction.EAST};
			case ASCENDING_WEST -> new Direction[]{Direction.WEST, Direction.EAST};
			case ASCENDING_NORTH -> new Direction[]{Direction.NORTH, Direction.SOUTH};
			case ASCENDING_SOUTH -> new Direction[]{Direction.NORTH, Direction.SOUTH};
			case SOUTH_EAST -> new Direction[]{Direction.SOUTH, Direction.EAST};
			case SOUTH_WEST -> new Direction[]{Direction.SOUTH, Direction.WEST};
			case NORTH_WEST -> new Direction[]{Direction.NORTH, Direction.WEST};
			case NORTH_EAST -> new Direction[]{Direction.NORTH, Direction.EAST};
		};
	}

	private static BlockPos railPos(ServerLevel level, BlockPos pos) {
		for (BlockPos candidate : new BlockPos[]{pos, pos.above(), pos.below()}) if (BaseRailBlock.isRail(level.getBlockState(candidate))) return candidate;
		return null;
	}
}
