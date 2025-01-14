package net.imprex.orebfuscator.obfuscation;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.bukkit.entity.Player;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;

import net.imprex.orebfuscator.Orebfuscator;
import net.imprex.orebfuscator.chunk.ChunkStruct;
import net.imprex.orebfuscator.config.AdvancedConfig;
import net.imprex.orebfuscator.config.OrebfuscatorConfig;
import net.imprex.orebfuscator.player.OrebfuscatorPlayer;
import net.imprex.orebfuscator.player.OrebfuscatorPlayerMap;
import net.imprex.orebfuscator.util.BlockPos;
import net.imprex.orebfuscator.util.OFCLogger;
import net.imprex.orebfuscator.util.PermissionUtil;

public abstract class ObfuscationListener extends PacketAdapter {

	private static final List<PacketType> PACKET_TYPES = List.of(
		PacketType.Play.Server.MAP_CHUNK,
		PacketType.Play.Server.UNLOAD_CHUNK,
		PacketType.Play.Server.LIGHT_UPDATE,
		PacketType.Play.Server.TILE_ENTITY_DATA
	);

	private final OrebfuscatorConfig config;
	private final OrebfuscatorPlayerMap playerMap;
	private final ObfuscationSystem obfuscationSystem;

	public ObfuscationListener(Orebfuscator orebfuscator) {
		super(orebfuscator, PACKET_TYPES.stream()
				.filter(PacketType::isSupported).toList());

		this.config = orebfuscator.getOrebfuscatorConfig();
		this.playerMap = orebfuscator.getPlayerMap();
		this.obfuscationSystem = orebfuscator.getObfuscationSystem();
	}

	protected abstract void preChunkProcessing(PacketEvent event);

	protected abstract void postChunkProcessing(PacketEvent event);

	public abstract void unregister();

	@Override
	public void onPacketSending(PacketEvent event) {
		if (event.getPacket().getType() != PacketType.Play.Server.MAP_CHUNK) {
			return;
		}

		Player player = event.getPlayer();
		if (this.shouldNotObfuscate(player)) {
			return;
		}

		ChunkStruct struct = new ChunkStruct(event.getPacket(), player.getWorld());
		if (struct.isEmpty()) {
			return;
		}

		this.preChunkProcessing(event);

		AdvancedConfig advancedConfig = this.config.advanced();

		CompletableFuture<ObfuscationResult> future = this.obfuscationSystem.obfuscate(struct);
		if (advancedConfig.hasObfuscationTimeout()) {
			future = future.orTimeout(advancedConfig.obfuscationTimeout(), TimeUnit.MILLISECONDS);
		}

		future.whenComplete((chunk, throwable) -> {
			if (throwable != null) {
				this.completeExceptionally(event, struct, throwable);
			} else if (chunk != null) {
				this.complete(event, struct, chunk);
			} else {
				OFCLogger.warn(String.format("skipping chunk[world=%s, x=%d, z=%d] because obfuscation result is missing",
						struct.world.getName(), struct.chunkX, struct.chunkZ));
				this.postChunkProcessing(event);
			}
		});
	}

	private boolean shouldNotObfuscate(Player player) {
		return PermissionUtil.canBypassObfuscate(player) || !config.world(player.getWorld()).needsObfuscation();
	}

	private void completeExceptionally(PacketEvent event, ChunkStruct struct, Throwable throwable) {
		if (throwable instanceof TimeoutException) {
			OFCLogger.warn(String.format("Obfuscation for chunk[world=%s, x=%d, z=%d] timed out",
					struct.world.getName(), struct.chunkX, struct.chunkZ));
		} else {
			OFCLogger.error(String.format("An error occurred while obfuscating chunk[world=%s, x=%d, z=%d]",
					struct.world.getName(), struct.chunkX, struct.chunkZ), throwable);
		}

		this.postChunkProcessing(event);
	}

	private void complete(PacketEvent event, ChunkStruct struct, ObfuscationResult chunk) {
		struct.setDataBuffer(chunk.getData());

		Set<BlockPos> blockEntities = chunk.getBlockEntities();
		if (!blockEntities.isEmpty()) {
			struct.removeBlockEntityIf(blockEntities::contains);
		}

		final OrebfuscatorPlayer player = this.playerMap.get(event.getPlayer());
		if (player != null) {
			player.addChunk(struct.chunkX, struct.chunkZ, chunk.getProximityBlocks());
		}

		this.postChunkProcessing(event);
	}
}
