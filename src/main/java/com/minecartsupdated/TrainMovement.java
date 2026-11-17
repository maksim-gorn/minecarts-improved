package com.minecartsupdated;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.MinecartFurnace;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class TrainMovement {
	private static final double CART_SPACING = 1.0;
	private static final Map<UUID, ArrayDeque<Vec3>> PATHS = new ConcurrentHashMap<>();
	private static final Map<UUID, Vec3> LAST_POSITIONS = new ConcurrentHashMap<>();

	static void tick(ServerLevel level) {
		TrainData data = TrainData.get(level.getServer());
		Set<UUID> visited = new HashSet<>();
		for (Entity entity : level.getAllEntities()) {
			if (!(entity instanceof AbstractMinecart cart) || data.neighbors(cart.getUUID()).isEmpty() || visited.contains(cart.getUUID())) continue;
			List<AbstractMinecart> train = collect(level, data, cart, visited);
			if (train.size() < 2) continue;
			MinecartFurnace engine = train.stream().filter(member -> member instanceof MinecartFurnace furnace && furnace.push.horizontalDistanceSqr() > 1.0E-7)
				.map(member -> (MinecartFurnace) member).findFirst().orElse(null);
			AbstractMinecart head = engine != null ? endpointInDirection(train, data, engine, engine.push) : chooseHead(train, data);
			train = order(level, data, head, train.size());
			if (engine != null && engine != head) head.setDeltaMovement(engine.getDeltaMovement());
			moveTrain(head, train);
			for (AbstractMinecart member : train) LAST_POSITIONS.put(member.getUUID(), member.position());
		}
	}

	static void forget(UUID cart) {
		PATHS.remove(cart);
		LAST_POSITIONS.remove(cart);
	}

	static void forget(Iterable<UUID> carts) {
		for (UUID cart : carts) forget(cart);
	}

	private static List<AbstractMinecart> collect(ServerLevel level, TrainData data, AbstractMinecart start, Set<UUID> visited) {
		List<AbstractMinecart> result = new ArrayList<>();
		ArrayDeque<AbstractMinecart> queue = new ArrayDeque<>();
		queue.add(start);
		while (!queue.isEmpty()) {
			AbstractMinecart cart = queue.remove();
			if (!visited.add(cart.getUUID())) continue;
			result.add(cart);
			for (UUID id : data.neighbors(cart.getUUID())) {
				if (level.getEntity(id) instanceof AbstractMinecart next && next.isAlive()) queue.add(next);
			}
		}
		return result;
	}

	private static AbstractMinecart chooseHead(List<AbstractMinecart> train, TrainData data) {
		List<AbstractMinecart> ends = train.stream().filter(cart -> data.neighbors(cart.getUUID()).size() == 1).toList();
		AbstractMinecart movingEnd = ends.stream().max((a, b) -> Double.compare(displacement(a), displacement(b))).orElse(null);
		AbstractMinecart activeEnd = ends.stream().filter(cart -> PATHS.containsKey(cart.getUUID())).findFirst().orElse(null);
		if (movingEnd != null && displacement(movingEnd) > 0.001
			&& (activeEnd == null || displacement(movingEnd) > displacement(activeEnd) + 0.002)) return movingEnd;
		if (activeEnd != null) return activeEnd;
		return ends.stream().min((a, b) -> a.getUUID().compareTo(b.getUUID())).orElse(train.getFirst());
	}

	private static AbstractMinecart endpointInDirection(List<AbstractMinecart> train, TrainData data, MinecartFurnace engine, Vec3 push) {
		return train.stream().filter(cart -> data.neighbors(cart.getUUID()).size() == 1)
			.max((a, b) -> Double.compare(alignment(engine, push, a), alignment(engine, push, b))).orElse(engine);
	}

	private static double alignment(MinecartFurnace engine, Vec3 push, AbstractMinecart endpoint) {
		Vec3 toward = endpoint.position().subtract(engine.position()).horizontal();
		return toward.horizontalDistanceSqr() < 1.0E-7 ? 0.0 : toward.normalize().dot(push.normalize());
	}

	private static double displacement(AbstractMinecart cart) {
		Vec3 previous = LAST_POSITIONS.get(cart.getUUID());
		return previous == null ? 0.0 : previous.distanceToSqr(cart.position());
	}

	private static List<AbstractMinecart> order(ServerLevel level, TrainData data, AbstractMinecart head, int size) {
		List<AbstractMinecart> ordered = new ArrayList<>();
		AbstractMinecart previous = null;
		AbstractMinecart current = head;
		while (current != null && ordered.size() < size) {
			ordered.add(current);
			UUID nextId = null;
			for (UUID candidate : data.neighbors(current.getUUID())) {
				if (previous == null || !candidate.equals(previous.getUUID())) {
					nextId = candidate;
					break;
				}
			}
			previous = current;
			current = nextId != null && level.getEntity(nextId) instanceof AbstractMinecart next ? next : null;
		}
		return ordered;
	}

	private static void moveTrain(AbstractMinecart head, List<AbstractMinecart> train) {
		for (AbstractMinecart member : train) if (member != head) PATHS.remove(member.getUUID());
		ArrayDeque<Vec3> path = PATHS.computeIfAbsent(head.getUUID(), ignored -> new ArrayDeque<>());
		if (path.isEmpty()) {
			for (int i = train.size() - 1; i >= 0; i--) path.addLast(train.get(i).position());
		}
		Vec3 position = head.position();
		if (path.isEmpty() || path.getLast().distanceToSqr(position) > 1.0E-5) path.addLast(position);
		while (path.size() > 2 && pathLength(path) > CART_SPACING * train.size() + 3.0) path.removeFirst();
		double speed = path.size() > 1 ? path.getLast().distanceTo(path.stream().skip(path.size() - 2L).findFirst().orElse(path.getLast())) : 0.0;
		for (int i = 1; i < train.size(); i++) {
			Vec3 followerPosition = behind(path, CART_SPACING * i);
			if (followerPosition == null) continue;
			AbstractMinecart follower = train.get(i);
			follower.setPos(followerPosition);
			Vec3 tangent = tangentBehind(path, CART_SPACING * i);
			if (tangent != null) {
				follower.setDeltaMovement(tangent.scale(speed));
				follower.setYRot((float)(180.0 - Math.toDegrees(Math.atan2(tangent.z, tangent.x))));
			}
		}
	}

	private static double pathLength(ArrayDeque<Vec3> path) {
		double length = 0.0;
		Vec3 previous = null;
		for (Vec3 point : path) {
			if (previous != null) length += previous.distanceTo(point);
			previous = point;
		}
		return length;
	}

	private static Vec3 behind(ArrayDeque<Vec3> path, double distance) {
		if (path.size() < 2) return null;
		List<Vec3> points = new ArrayList<>(path);
		Vec3 end = points.getLast();
		for (int i = points.size() - 1; i > 0; i--) {
			Vec3 start = points.get(i - 1);
			double segment = start.distanceTo(end);
			if (segment >= distance) return end.lerp(start, distance / segment);
			distance -= segment;
			end = start;
		}
		return null;
	}

	private static Vec3 tangentBehind(ArrayDeque<Vec3> path, double distance) {
		Vec3 near = behind(path, distance);
		Vec3 far = behind(path, distance + 0.1);
		if (near == null || far == null) return null;
		return near.subtract(far).normalize();
	}
}
