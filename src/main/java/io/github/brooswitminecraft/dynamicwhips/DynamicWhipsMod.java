package io.github.brooswitminecraft.dynamicwhips;

import io.github.brooswitminecraft.dynamicwhips.rope.RopeManager;
import io.github.brooswitminecraft.dynamicwhips.rope.client.ClientRopeState;
import io.github.brooswitminecraft.dynamicwhips.rope.client.RopeRenderer;
import io.github.brooswitminecraft.dynamicwhips.rope.net.RopeRemovePayload;
import io.github.brooswitminecraft.dynamicwhips.rope.net.RopeSyncPayload;
import net.minecraft.server.level.ServerPlayer;
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
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Entry point and registry for Dynamic Whips (MINECRAFT-67): the Leather Whip, the Sable rope
 * spike debug command, and the rope core (MINECRAFT-85) the whip anchor, grappling hooks and
 * harpoon will build on.
 */
@Mod(DynamicWhipsMod.MODID)
public class DynamicWhipsMod {
    public static final String MODID = "dynamicwhips";

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);

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

    public DynamicWhipsMod(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        modEventBus.addListener(this::addCreative);
        modEventBus.addListener(this::registerPayloads);

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
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> {
            RopeManager.detachAllOwnedBy(e.getEntity().getUUID());
            WhipHoldState.clear(e.getEntity().getUUID());
            HookState.clear(e.getEntity().getUUID());
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> {
            RopeManager.detachAllOwnedBy(e.getEntity().getUUID());
            WhipHoldState.clear(e.getEntity().getUUID());
            HookState.clear(e.getEntity().getUUID());
        });
        NeoForge.EVENT_BUS.addListener((LivingDeathEvent e) -> {
            if (e.getEntity() instanceof Player player) {
                RopeManager.detachAllOwnedBy(player.getUUID());
                WhipHoldState.clear(player.getUUID());
                HookState.clear(player.getUUID());
            }
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> {
            RopeManager.clearAll();
            WhipHoldState.clearAll();
            HookState.clearAll();
        });

        // The renderer (and the whip's/hook's own client-tick senders) touch Minecraft client
        // classes that do not exist on a dedicated server; this guard stops any of them from ever
        // being loaded there (see docs/rope-core.md and docs/whip.md section 1).
        if (FMLEnvironment.dist.isClient()) {
            NeoForge.EVENT_BUS.addListener(RopeRenderer::onRenderLevelStage);
            NeoForge.EVENT_BUS.addListener(WhipClientInput::onClientTick);
            NeoForge.EVENT_BUS.addListener(HookClientInput::onClientTick);
        }
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.COMBAT) {
            event.accept(LEATHER_WHIP);
        }
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(IRON_HOOK);
            event.accept(DIAMOND_HOOK);
            event.accept(NETHERITE_HOOK);
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
    }
}
