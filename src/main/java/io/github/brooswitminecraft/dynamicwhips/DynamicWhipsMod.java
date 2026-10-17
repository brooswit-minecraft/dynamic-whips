package io.github.brooswitminecraft.dynamicwhips;

import io.github.brooswitminecraft.dynamicwhips.rope.RopeManager;
import io.github.brooswitminecraft.dynamicwhips.rope.client.ClientRopeState;
import io.github.brooswitminecraft.dynamicwhips.rope.client.RopeRenderer;
import io.github.brooswitminecraft.dynamicwhips.rope.net.RopeRemovePayload;
import io.github.brooswitminecraft.dynamicwhips.rope.net.RopeSyncPayload;
import io.github.brooswitminecraft.magicavoxelloader.vox.MinecraftItemModel;
import io.github.brooswitminecraft.magicavoxelloader.vox.MinecraftItemModelConverter;
import io.github.brooswitminecraft.magicavoxelloader.vox.VoxAssetLoader;
import io.github.brooswitminecraft.magicavoxelloader.vox.VoxelModel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.io.IOException;
import java.io.InputStream;

/**
 * Entry point and registry for Dynamic Whips (MINECRAFT-67): the Leather Whip, the Sable rope
 * spike debug command, and the rope core (MINECRAFT-85) the whip anchor, grappling hooks and
 * harpoon will build on.
 */
@Mod(DynamicWhipsMod.MODID)
public class DynamicWhipsMod {
    public static final String MODID = "dynamicwhips";

    /**
     * In-world entities owned by this mod.  The Kickball renderer is intentionally registered by
     * the later model-loading story; this foundation only provides its normal vanilla entity seam.
     */
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

    /** The generic playground-ball entity used by the crafting, collision, and damping stories. */
    public static final DeferredHolder<EntityType<?>, EntityType<KickballEntity>> KICKBALL_ENTITY = ENTITIES.register("kickball",
            () -> EntityType.Builder.<KickballEntity>of(KickballEntity::new, MobCategory.MISC)
                    .sized(0.75F, 0.75F).clientTrackingRange(8).updateInterval(1)
                    .build(ResourceLocation.fromNamespaceAndPath(MODID, "kickball").toString()));

    /** The thrown harpoon projectile (MINECRAFT-88): sized/tracked like vanilla's own thrown
     * trident, the closest vanilla analogue (a thrown, player-owned, stick-on-hit projectile). */
    public static final DeferredHolder<EntityType<?>, EntityType<HarpoonEntity>> HARPOON_ENTITY = ENTITIES.register("harpoon",
            () -> EntityType.Builder.<HarpoonEntity>of(HarpoonEntity::new, MobCategory.MISC)
                    .sized(0.5F, 0.5F).clientTrackingRange(4).updateInterval(20)
                    .build(ResourceLocation.fromNamespaceAndPath(MODID, "harpoon").toString()));

    public static final DeferredItem<WhipItem> LEATHER_WHIP = ITEMS.register("leather_whip",
            () -> new WhipItem(new Item.Properties().stacksTo(1).durability(WhipLogic.DURABILITY)));

    // Grappling hooks (MINECRAFT-87 criterion 9): one shared HookItem class, tier as data
    // (HookLogic.Tier) — no durability, same reasoning as vanilla's own compass/spyglass: this is
    // a validated-server-side traversal tool, not a consumable, and its real progression cost is
    // paid once at crafting time (see docs/hooks.md).
    public static final DeferredItem<HookItem> IRON_HOOK = ITEMS.register("iron_hook",
            () -> new HookItem(HookLogic.Tier.IRON, new Item.Properties().stacksTo(1)));
    public static final DeferredItem<HookItem> DIAMOND_HOOK = ITEMS.register("diamond_hook",
            () -> new HookItem(HookLogic.Tier.DIAMOND, new Item.Properties().stacksTo(1)));
    public static final DeferredItem<HookItem> NETHERITE_HOOK = ITEMS.register("netherite_hook",
            () -> new HookItem(HookLogic.Tier.NETHERITE, new Item.Properties().stacksTo(1).fireResistant()));

    // The harpoon (MINECRAFT-88, AC9): a weapon/control tool, not a traversal item — see
    // HarpoonLogic/HarpoonItem's own javadoc. Not consumed on throw; durability is charged once
    // per throw, same convention as the whip and the hooks' own per-use costs.
    public static final DeferredItem<HarpoonItem> HARPOON_ITEM = ITEMS.register("harpoon",
            () -> new HarpoonItem(new Item.Properties().stacksTo(1).durability(HarpoonLogic.DURABILITY)));

    private static volatile VoxelModel kickballVoxelModel;
    private static volatile MinecraftItemModel kickballItemModel;
    public static final DeferredItem<KickballItem> KICKBALL_ITEM = ITEMS.register("kickball",
            () -> new KickballItem(new Item.Properties().stacksTo(1)));

    /** Compatibility alias for the renderer and tests from the merged consumer story. */
    public static final DeferredHolder<EntityType<?>, EntityType<KickballEntity>> KICKBALL = KICKBALL_ENTITY;

    public DynamicWhipsMod(IEventBus modEventBus) {
        ENTITIES.register(modEventBus);
        ITEMS.register(modEventBus);
        modEventBus.addListener(this::addCreative);
        modEventBus.addListener(this::registerPayloads);
        modEventBus.addListener(this::loadKickballModel);
        if (FMLEnvironment.dist.isClient()) {
            modEventBus.addListener(io.github.brooswitminecraft.dynamicwhips.client.KickballClient::registerRenderers);
        }

        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> RopeSpike.register(e.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> RopeSpike.tick(e.getServer()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> RopeSpike.clear(e.getEntity().getUUID()));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> RopeSpike.clearAll());

        // Rope core lifecycle (MINECRAFT-85 acceptance criterion 4): detach on request happens at
        // the call site (whip/hook/harpoon logic, not wired up by this story); everything else —
        // death, logout, dimension change, server stop, and anchor chunk unload/block break caught
        // inside RopeManager.tickAll via PlayerRope.isLive — is driven from here.
        //
        // MINECRAFT-86 criterion 8: the whip's own "who is currently holding/attached" bookkeeping
        // (WhipHoldState) is a SEPARATE thing from the rope itself and does not get cleared just
        // because RopeManager tears the rope down — so every one of these same events also clears
        // it, alongside (not instead of) the rope-core call already here.
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> RopeManager.tickAll(e.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> WhipHoldState.tickTimeouts(e.getServer()));
        // HookState has no ping-silence timeout of its own (a hook stays connected unheld, unlike
        // the whip — see HookState's javadoc): this only ever applies the latest reel/pay-out
        // input, one rope segment per tick (MINECRAFT-87 criteria 3/4/6).
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> HookState.tickAll(e.getServer()));
        // HarpoonState mirrors HookState (MINECRAFT-88): a thrown-and-stuck harpoon stays
        // connected unheld too, so this only ever applies the latest reel/pay-out input.
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> HarpoonState.tickAll(e.getServer()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            RopeManager.detachAllOwnedBy(e.getEntity().getUUID());
            WhipHoldState.clear(e.getEntity().getUUID());
            HookState.clear(e.getEntity().getUUID());
            HarpoonState.clear(e.getEntity().getUUID());
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> {
            RopeManager.detachAllOwnedBy(e.getEntity().getUUID());
            WhipHoldState.clear(e.getEntity().getUUID());
            HookState.clear(e.getEntity().getUUID());
            HarpoonState.clear(e.getEntity().getUUID());
        });
        NeoForge.EVENT_BUS.addListener((LivingDeathEvent e) -> {
            if (e.getEntity() instanceof Player player) {
                RopeManager.detachAllOwnedBy(player.getUUID());
                WhipHoldState.clear(player.getUUID());
                HookState.clear(player.getUUID());
                HarpoonState.clear(player.getUUID());
            }
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> {
            RopeManager.clearAll();
            WhipHoldState.clearAll();
            HookState.clearAll();
            HarpoonState.clearAll();
        });

        // The renderer (and the whip's/hook's own client-tick senders) touch Minecraft client
        // classes that do not exist on a dedicated server; this guard stops any of them from ever
        // being loaded there (see docs/rope-core.md and docs/whip.md section 1).
        if (FMLEnvironment.dist.isClient()) {
            NeoForge.EVENT_BUS.addListener(RopeRenderer::onRenderLevelStage);
            NeoForge.EVENT_BUS.addListener(WhipClientInput::onClientTick);
            NeoForge.EVENT_BUS.addListener(HookClientInput::onClientTick);
            NeoForge.EVENT_BUS.addListener(HarpoonClientInput::onClientTick);
            NeoForge.EVENT_BUS.addListener(io.github.brooswitminecraft.dynamicwhips.client.KickballClient::onRenderFrame);
        }
    }

    private void loadKickballModel(net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent ignored) {
        try (InputStream stream = getClass().getResourceAsStream("/assets/" + MODID + "/vox/kickball.vox")) {
            if (stream == null) {
                throw new IllegalStateException("Packaged kickball.vox is missing");
            }
            VoxelModel vox = VoxAssetLoader.load(stream);
            kickballVoxelModel = vox;
            kickballItemModel = MinecraftItemModelConverter.convert(vox);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load packaged Kickball MagicaVoxel asset", e);
        }
    }

    public static VoxelModel kickballVoxelModel() {
        if (kickballVoxelModel == null) throw new IllegalStateException("Kickball model requested before common setup");
        return kickballVoxelModel;
    }

    public static MinecraftItemModel kickballItemModel() {
        if (kickballItemModel == null) throw new IllegalStateException("Kickball native model requested before common setup");
        return kickballItemModel;
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.COMBAT) {
            event.accept(LEATHER_WHIP);
            event.accept(HARPOON_ITEM);
        }
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(IRON_HOOK);
            event.accept(DIAMOND_HOOK);
            event.accept(NETHERITE_HOOK);
            event.accept(KICKBALL_ITEM);
        }
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToClient(RopeSyncPayload.TYPE, RopeSyncPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientRopeState.handleSync(payload)));
        registrar.playToClient(RopeRemovePayload.TYPE, RopeRemovePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> ClientRopeState.handleRemove(payload)));
        registrar.playToServer(WhipHoldPingPayload.TYPE, WhipHoldPingPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        WhipHoldState.ping(serverPlayer.getUUID(), serverPlayer.level().getGameTime());
                    }
                }));
        registrar.playToServer(WhipReleasePayload.TYPE, WhipReleasePayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        WhipHoldState.releaseNow(serverPlayer.getUUID());
                    }
                }));
        registrar.playToServer(HookInputPayload.TYPE, HookInputPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        HookState.setInput(serverPlayer.getUUID(), payload.reelIn(), payload.payOut(),
                                serverPlayer.level().getGameTime());
                    }
                }));
        registrar.playToServer(HarpoonInputPayload.TYPE, HarpoonInputPayload.STREAM_CODEC,
                (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer serverPlayer) {
                        HarpoonState.setInput(serverPlayer.getUUID(), payload.reelIn(), payload.payOut(),
                                serverPlayer.level().getGameTime());
                    }
                }));
    }
}
