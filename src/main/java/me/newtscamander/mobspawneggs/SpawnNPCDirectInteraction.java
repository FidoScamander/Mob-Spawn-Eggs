package me.newtscamander.mobspawneggs;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.WaitForDataFrom;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.transaction.ItemStackSlotTransaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.CooldownHandler;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.SimpleInstantInteraction;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.spawning.SpawnTestResult;
import org.joml.Vector3d;
import org.joml.Vector3i;

import java.util.Locale;

public final class SpawnNPCDirectInteraction extends SimpleInstantInteraction {
    public static final BuilderCodec<SpawnNPCDirectInteraction> CODEC = BuilderCodec
            .builder(SpawnNPCDirectInteraction.class, SpawnNPCDirectInteraction::new, SimpleInstantInteraction.CODEC)
            .addField(new KeyedCodec<>("EntityId", Codec.STRING),
                    (interaction, entityId) -> interaction.entityId = entityId,
                    interaction -> interaction.entityId)
            .documentation("Server-authoritative direct NPC spawn using the player's look raycast.")
            .build();

    private static final double MIN_DOWN_PITCH_DEGREES = -20.0;
    private static final double MAX_DOWN_PITCH_DEGREES = -90.0;
    private static final double TARGET_DISTANCE = 8.0;

    private String entityId;

    @Override
    public WaitForDataFrom getWaitForDataFrom() {
        return WaitForDataFrom.Server;
    }

    @Override
    protected void firstRun(InteractionType type, InteractionContext context, CooldownHandler cooldownHandler) {
        try {
            if (context == null || entityId == null || entityId.isBlank()) {
                fail(context);
                MobSpawnEggsPlugin.debug("DIRECT blocked: empty EntityId");
                return;
            }

            CommandBuffer<EntityStore> commandBuffer = context.getCommandBuffer();
            Ref<EntityStore> entity = context.getEntity();
            if (commandBuffer == null || entity == null) {
                fail(context);
                MobSpawnEggsPlugin.debug("DIRECT blocked: missing command buffer/entity for " + entityId);
                return;
            }

            String worldName = worldName(commandBuffer);
            PlayerRef player = playerRef(context, commandBuffer);
            String username = player == null ? "unknown" : player.getUsername();

            if (MobSpawnEggsPlugin.isWorldBlacklisted(worldName)) {
                fail(context);
                MobSpawnEggsPlugin.sendTranslated(player, "server.messages.MSE.worldBlocked");
                MobSpawnEggsPlugin.debug("DIRECT blocked in blacklisted world " + worldName);
                return;
            }

            double pitch = Math.toDegrees(TargetUtil.getLook(entity, commandBuffer).getRotation().pitch());
            if (pitch > MIN_DOWN_PITCH_DEGREES || pitch < MAX_DOWN_PITCH_DEGREES) {
                fail(context);
                MobSpawnEggsPlugin.sendTranslated(player, "server.messages.MSE.directAim");
                MobSpawnEggsPlugin.debug(String.format(Locale.ROOT,
                        "DIRECT gate pitch=%.2f allowed=false reason=pitch", pitch));
                return;
            }

            Vector3i target = TargetUtil.getTargetBlock(entity, TARGET_DISTANCE, commandBuffer);
            if (target == null) {
                fail(context);
                MobSpawnEggsPlugin.sendTranslated(player, "server.messages.MSE.directAim");
                MobSpawnEggsPlugin.debug(String.format(Locale.ROOT,
                        "DIRECT gate pitch=%.2f allowed=false reason=no-target", pitch));
                return;
            }

            ItemContainer container = context.getHeldItemContainer();
            short slot = (short) context.getHeldItemSlot();
            ItemStack heldItem = context.getHeldItem();
            if (container == null || heldItem == null || heldItem.isEmpty()) {
                fail(context);
                MobSpawnEggsPlugin.debug("DIRECT blocked: held egg missing for " + entityId);
                return;
            }

            int x = target.x;
            int y = target.y;
            int z = target.z;
            if (context.getState() != null) context.getState().state = InteractionState.Finished;

            MobSpawnEggsPlugin.debug(String.format(Locale.ROOT,
                    "DIRECT gate pitch=%.2f allowed=true target=%d/%d/%d", pitch, x, y, z));

            String spawnEntityId = entityId;
            commandBuffer.run(store -> spawnAndConsume(
                    store, spawnEntityId, username, player, worldName,
                    container, slot, heldItem, x, y, z));
        } catch (Throwable error) {
            fail(context);
            MobSpawnEggsPlugin.warning("DIRECT interaction failed for " + entityId, error);
        }
    }

    private static void spawnAndConsume(Store<EntityStore> store, String entityId, String username,
                                        PlayerRef player, String worldName, ItemContainer container,
                                        short slot, ItemStack heldItem, int x, int y, int z) {
        Vector3d spawn = new Vector3d(x + 0.5, y + 1.0, z + 0.5);
        try {
            SpawnTestResult result = NPCPlugin.get().spawnNPCWithSpaceValidation(
                    store, entityId, null, spawn, new Rotation3f());
            if (result != SpawnTestResult.TEST_OK) {
                MobSpawnEggsPlugin.auditFailSpawn(entityId, username, worldName,
                        spawn.x, spawn.y, spawn.z, String.valueOf(result));
                MobSpawnEggsPlugin.sendTranslated(player, "server.messages.MSE.spawnFailed");
                MobSpawnEggsPlugin.debug("DIRECT spawn rejected for " + entityId + ": " + result);
                return;
            }

            boolean consumed = consumeOne(container, slot, heldItem);
            MobSpawnEggsPlugin.auditSpawn(entityId, username, worldName, spawn.x, spawn.y, spawn.z);
            MobSpawnEggsPlugin.debug("DIRECT spawned " + entityId + ", consumed=" + consumed);
            if (!consumed) {
                MobSpawnEggsPlugin.warning("DIRECT spawned " + entityId + " but could not consume the egg", null);
            }
        } catch (Throwable error) {
            MobSpawnEggsPlugin.auditFailSpawn(entityId, username, worldName,
                    spawn.x, spawn.y, spawn.z, error.getClass().getSimpleName());
            MobSpawnEggsPlugin.sendTranslated(player, "server.messages.MSE.spawnFailed");
            MobSpawnEggsPlugin.warning("DIRECT spawn failed for " + entityId, error);
        }
    }

    private static boolean consumeOne(ItemContainer container, short slot, ItemStack heldItem) {
        try {
            ItemStackSlotTransaction transaction = container.removeItemStackFromSlot(slot, heldItem, 1);
            return transaction != null && transaction.succeeded();
        } catch (Throwable error) {
            MobSpawnEggsPlugin.warning("DIRECT egg consumption error", error);
            return false;
        }
    }

    private static PlayerRef playerRef(InteractionContext context, CommandBuffer<EntityStore> commandBuffer) {
        try {
            Ref<EntityStore> owner = context.getOwningEntity();
            return owner == null ? null : (PlayerRef) commandBuffer.getComponent(owner, PlayerRef.getComponentType());
        } catch (Throwable error) {
            MobSpawnEggsPlugin.debug("DIRECT player lookup failed: "
                    + error.getClass().getSimpleName() + ": " + error.getMessage());
            return null;
        }
    }

    private static String worldName(CommandBuffer<EntityStore> commandBuffer) {
        try {
            EntityStore store = (EntityStore) commandBuffer.getExternalData();
            if (store != null && store.getWorld() != null) return store.getWorld().getName();
        } catch (Throwable error) {
            MobSpawnEggsPlugin.debug("DIRECT world lookup failed: "
                    + error.getClass().getSimpleName() + ": " + error.getMessage());
        }
        return "unknown";
    }

    private static void fail(InteractionContext context) {
        if (context != null && context.getState() != null) context.getState().state = InteractionState.Failed;
    }
}
