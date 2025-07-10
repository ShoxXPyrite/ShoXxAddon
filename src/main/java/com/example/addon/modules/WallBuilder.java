package com.example.addon.modules;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

public class WallBuilder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgPattern1 = settings.createGroup("Pattern 1");
    private final SettingGroup sgPattern2 = settings.createGroup("Pattern 2");

    private boolean usingPattern1 = true;
    private int buildStep = 0;
    private State state = State.BUILDING;

    private final Setting<Boolean> usePattern2 = sgGeneral.add(new BoolSetting.Builder()
        .name("use-pattern-2")
        .description("Whether to use pattern 2 after pattern 1.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Delay between placing blocks (in milliseconds).")
        .defaultValue(150)
        .min(0)
        .sliderMax(500)
        .build()
    );

    private final Setting<Integer> placeRetries = sgGeneral.add(new IntSetting.Builder()
        .name("place-retries")
        .description("How many times to retry placing a block if it fails.")
        .defaultValue(3)
        .min(1)
        .sliderMax(10)
        .build()
    );

    private final Setting<Integer> patternDelay = sgGeneral.add(new IntSetting.Builder()
        .name("pattern-delay")
        .description("Delay after completing a pattern (in ticks).")
        .defaultValue(20)
        .min(1)
        .sliderMax(100)
        .build()
    );

    private final Setting<Boolean> autoWalk = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-walk")
        .description("Automatically walk backwards after building using Baritone pathfinding.")
        .defaultValue(true)
        .build()
    );

    // Render settings
    private final Setting<Boolean> renderOverlay = sgRender.add(new BoolSetting.Builder()
        .name("render-overlay")
        .description("Render block overlay for the wall pattern.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> renderSides = sgRender.add(new BoolSetting.Builder()
        .name("render-sides")
        .description("Render the outline of blocks.")
        .defaultValue(true)
        .visible(renderOverlay::get)
        .build()
    );

    private final Setting<Boolean> renderFill = sgRender.add(new BoolSetting.Builder()
        .name("render-fill")
        .description("Render the fill of blocks.")
        .defaultValue(true)
        .visible(renderOverlay::get)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("Color of the block outline.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .visible(() -> renderOverlay.get() && renderSides.get())
        .build()
    );

    private final Setting<SettingColor> fillColor = sgRender.add(new ColorSetting.Builder()
        .name("fill-color")
        .description("Color of the block fill.")
        .defaultValue(new SettingColor(255, 255, 255, 30))
        .visible(() -> renderOverlay.get() && renderFill.get())
        .build()
    );

    private final Setting<Boolean>[][] pattern1 = new Setting[5][5];
    private final Setting<Boolean>[][] pattern2 = new Setting[5][5];

    private long lastPlaceTime = 0;
    private int walkTicks = 0;
    private BlockPos startPos;
    private int currentRetries = 0;
    private final List<BlockPos> placedBlocks = new ArrayList<>();

    // Baritone integration - simplified approach
    private boolean baritoneAvailable = false;

    private enum State {
        BUILDING,
        WALKING,
        WAITING
    }

    public WallBuilder() {
        super(Categories.World, "wall-builder", "Builds a 5x5 obsidian wall in front of you based on patterns.");

        // Create Pattern 1
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 5; j++) {
                final int x = j;
                final int y = i;
                pattern1[i][j] = sgPattern1.add(new BoolSetting.Builder()
                    .name("Block " + (i * 5 + j + 1))
                    .description("Block at position (" + x + "," + y + ")")
                    .defaultValue(false)
                    .build()
                );
            }
        }

        // Create Pattern 2
        for (int i = 0; i < 5; i++) {
            for (int j = 0; j < 5; j++) {
                final int x = j;
                final int y = i;
                pattern2[i][j] = sgPattern2.add(new BoolSetting.Builder()
                    .name("Block " + (i * 5 + j + 1))
                    .description("Block at position (" + x + "," + y + ")")
                    .defaultValue(false)
                    .visible(usePattern2::get)
                    .build()
                );
            }
        }
    }

    @Override
    public void onActivate() {
        buildStep = 0;
        usingPattern1 = true;
        walkTicks = 0;
        startPos = null;
        state = State.BUILDING;
        currentRetries = 0;
        placedBlocks.clear();

        // Check if Baritone is available
        checkBaritone();
    }

    @Override
    public void onDeactivate() {
        placedBlocks.clear();
        // Stop Baritone if it's running
        stopBaritone();
    }

    private void checkBaritone() {
        try {
            // Simple check if Baritone classes exist
            Class.forName("baritone.api.BaritoneAPI");
            baritoneAvailable = true;
            info("Baritone detected - auto-walk enabled");
        } catch (ClassNotFoundException e) {
            baritoneAvailable = false;
            warning("Baritone not found - auto-walk disabled. Install Baritone for full functionality.");
        }
    }

    private void stopBaritone() {
        if (baritoneAvailable) {
            try {
                // Use runtime command to stop Baritone
                mc.getNetworkHandler().sendChatMessage("#stop");
            } catch (Exception e) {
                error("Failed to stop Baritone: " + e.getMessage());
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        switch (state) {
            case BUILDING:
                build();
                break;
            case WALKING:
                walk();
                break;
            case WAITING:
                wait_state();
                break;
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (!renderOverlay.get() || placedBlocks.isEmpty()) return;

        for (BlockPos pos : placedBlocks) {
            if (renderSides.get() && renderFill.get()) {
                event.renderer.box(pos, sideColor.get(), fillColor.get(), ShapeMode.Both, 0);
            } else if (renderSides.get()) {
                event.renderer.box(pos, sideColor.get(), sideColor.get(), ShapeMode.Lines, 0);
            } else if (renderFill.get()) {
                event.renderer.box(pos, fillColor.get(), fillColor.get(), ShapeMode.Sides, 0);
            }
        }
    }

    private void build() {
        if (System.currentTimeMillis() - lastPlaceTime < placeDelay.get()) return;

        Direction facing = mc.player.getHorizontalFacing();
        BlockPos start = mc.player.getBlockPos().offset(facing);

        Setting<Boolean>[][] currentPattern = usingPattern1 ? pattern1 : pattern2;

        for (; buildStep < 25; buildStep++) {
            int x = buildStep % 5;
            int y = buildStep / 5;
            if (!currentPattern[y][x].get()) continue;

            BlockPos pos = start.add(
                facing.rotateYClockwise().getOffsetX() * (x - 2),
                y,
                facing.rotateYClockwise().getOffsetZ() * (x - 2)
            );

            // Check if the block is already placed
            if (!mc.world.getBlockState(pos).isReplaceable()) {
                continue; // Block is already placed, move to the next one
            }

            if (placeBlock(pos)) {
                currentRetries = 0;
                return;
            }

            if (currentRetries < placeRetries.get()) {
                currentRetries++;
                return;
            }

            currentRetries = 0;
        }

        if (buildStep >= 25) {
            // Pattern completed
            if (autoWalk.get() && baritoneAvailable) {
                // Start auto-walk using Baritone
                startPos = mc.player.getBlockPos();
                state = State.WALKING;
                startBaritoneWalk();
            } else {
                // Just wait and switch patterns
                state = State.WAITING;
                walkTicks = patternDelay.get();
            }
        }
    }

    private void startBaritoneWalk() {
        if (!baritoneAvailable) return;

        try {
            Direction facing = mc.player.getHorizontalFacing();
            String command;
            
            switch (facing) {
                case NORTH:
                    command = "#goto ~ ~ ~+1";
                    break;
                case EAST:
                    command = "#goto ~-1 ~ ~";
                    break;
                case SOUTH:
                    command = "#goto ~ ~ ~-1";
                    break;
                case WEST:
                    command = "#goto ~+1 ~ ~";
                    break;
                default:
                    command = "#goto ~ ~ ~-1"; // fallback
                    break;
            }
            
            mc.getNetworkHandler().sendChatMessage(command);
            info("Started Baritone auto-walk: moving 1 block backwards from " + facing.toString().toLowerCase());

        } catch (Exception e) {
            error("Failed to start Baritone walk: " + e.getMessage());
            // Fallback to waiting state
            state = State.WAITING;
            walkTicks = patternDelay.get();
        }
    }

    private void walk() {
        if (!baritoneAvailable) {
            // Baritone not available, skip to waiting
            state = State.WAITING;
            walkTicks = patternDelay.get();
            return;
        }

        // Just wait for the pattern delay - Baritone will handle the movement automatically
        walkTicks++;
        if (walkTicks >= patternDelay.get()) {
            // Move to waiting state after delay
            state = State.WAITING;
            walkTicks = patternDelay.get();
        }
    }

    private void wait_state() {
        if (walkTicks > 0) {
            walkTicks--;
            return;
        }

        // Reset walk counter
        walkTicks = 0;

        // Switch patterns and restart building continuously
        buildStep = 0;

        if (usePattern2.get()) {
            usingPattern1 = !usingPattern1;
            // Clear placed blocks when switching patterns so old blocks don't show
            placedBlocks.clear();
        }

        state = State.BUILDING;
    }

    private boolean placeBlock(BlockPos pos) {
        FindItemResult obsidian = InvUtils.findInHotbar(Blocks.OBSIDIAN.asItem());
        if (!obsidian.found()) {
            error("No obsidian blocks in hotbar!");
            toggle();
            return false;
        }

        InvUtils.swap(obsidian.slot(), false);

        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos), Direction.UP, pos, false);

        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND,
            BlockPos.ORIGIN,
            Direction.DOWN
        ));

        mc.player.networkHandler.sendPacket(new PlayerInteractBlockC2SPacket(
            Hand.OFF_HAND,
            hit,
            mc.player.currentScreenHandler.getRevision() + 2
        ));

        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND,
            BlockPos.ORIGIN,
            Direction.DOWN
        ));

        mc.player.swingHand(Hand.MAIN_HAND);
        lastPlaceTime = System.currentTimeMillis();
        buildStep++;

        // Add to render list immediately (assuming placement will succeed)
        placedBlocks.add(pos);

        return true;
    }
}
