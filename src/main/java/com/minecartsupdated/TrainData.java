package com.minecartsupdated;

import com.mojang.serialization.Codec;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class TrainData extends SavedData {
	private static final Codec<TrainData> CODEC = Codec.STRING.listOf().xmap(TrainData::new, TrainData::encode);
	private static final SavedDataType<TrainData> TYPE = new SavedDataType<>(MinecartsUpdated.id("train_links"), TrainData::new, CODEC, null);
	private final Map<UUID, Set<UUID>> links = new HashMap<>();

	private TrainData() { }

	private TrainData(List<String> encoded) {
		for (String edge : encoded) {
			String[] parts = edge.split("\\|", 2);
			if (parts.length == 2) {
				try {
					add(UUID.fromString(parts[0]), UUID.fromString(parts[1]));
				} catch (IllegalArgumentException ignored) {
					// Ignore malformed links rather than preventing the world from loading.
				}
			}
		}
	}

	static TrainData get(MinecraftServer server) {
		return server.getDataStorage().computeIfAbsent(TYPE);
	}

	Set<UUID> neighbors(UUID cart) {
		return links.getOrDefault(cart, Set.of());
	}

	boolean connected(UUID first, UUID second) {
		return neighbors(first).contains(second);
	}

	void link(UUID first, UUID second) {
		add(first, second);
		setDirty();
	}

	void unlink(UUID first, UUID second) {
		remove(first, second);
		setDirty();
	}

	void unlinkAll(UUID cart) {
		for (UUID neighbor : Set.copyOf(neighbors(cart))) remove(cart, neighbor);
		setDirty();
	}

	private void add(UUID first, UUID second) {
		links.computeIfAbsent(first, ignored -> new HashSet<>()).add(second);
		links.computeIfAbsent(second, ignored -> new HashSet<>()).add(first);
	}

	private void remove(UUID first, UUID second) {
		links.computeIfPresent(first, (ignored, neighbors) -> { neighbors.remove(second); return neighbors.isEmpty() ? null : neighbors; });
		links.computeIfPresent(second, (ignored, neighbors) -> { neighbors.remove(first); return neighbors.isEmpty() ? null : neighbors; });
	}

	private List<String> encode() {
		List<String> result = new ArrayList<>();
		for (Map.Entry<UUID, Set<UUID>> entry : links.entrySet()) {
			for (UUID neighbor : entry.getValue()) {
				if (entry.getKey().compareTo(neighbor) < 0) result.add(entry.getKey() + "|" + neighbor);
			}
		}
		return result;
	}
}
