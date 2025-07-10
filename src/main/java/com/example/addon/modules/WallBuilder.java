package com.example.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class WallBuilder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
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

    private final Setting<Integer> walkDelay = sgGeneral.add(new IntSetting.Builder()
        .name("walk-delay")
        .description("Delay before walking back (in ticks).")
        .defaultValue(20)
        .min(1)
        .sliderMax(100)
        .build()
    );

    private final Setting<Boolean>[][] pattern1 = new Setting[5][5];
    private final Setting<Boolean>[][] pattern2 = new Setting[5][5];

    private long lastPlaceTime = 0;
    private int walkTicks = 0;
    private BlockPos startPos;
    private int currentRetries = 0;
    private BlockPos targetPos;
    private int maxWalkTicks = 0;

    private enum State {
        BUILDING,
        PATTERN_DELAY,
        CENTERING,
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
        targetPos = null;
        maxWalkTicks = 0;
    }

    @Override
    public void onDeactivate() {
        // Make sure we stop pressing the back key when module is turned off
        mc.options.backKey.setPressed(false);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        switch (state) {
            case BUILDING:
                build();
                break;
            case PATTERN_DELAY:
                patternDelay();
                break;
            case CENTERING:
                centerPlayer();
                break;
            case WALKING:
                walk();
                break;
            case WAITING:
                wait_state();
                break;
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
            state = State.PATTERN_DELAY;
            walkTicks = patternDelay.get();
        }
    }

    private void patternDelay() {
        if (walkTicks > 0) {
            walkTicks--;
            return;
        }

        state = State.CENTERING;
        startPos = mc.player.getBlockPos();
    }

    private void centerPlayer() {
        // Calculate the center of the current block
        double centerX = startPos.getX() + 0.5;
        double centerZ = startPos.getZ() + 0.5;

        // Get player position
        double playerX = mc.player.getX();
        double playerZ = mc.player.getZ();

        // Check if player is centered (with a small tolerance)
        double tolerance = 0.05;
        if (Math.abs(playerX - centerX) < tolerance && Math.abs(playerZ - centerZ) < tolerance) {
            // Player is centered, move to walking state
            state = State.WALKING;
            walkTicks = walkDelay.get();
            maxWalkTicks = 60; // Maximum 60 ticks (3 seconds) to walk back

            // Calculate target position (1 block back)
            Direction facing = mc.player.getHorizontalFacing().getOpposite(); // Opposite of player facing
            targetPos = startPos.offset(facing);
            return;
        }

        // Move player toward center
        double moveX = centerX - playerX;
        double moveZ = centerZ - playerZ;

        // Normalize movement vector
        double length = Math.sqrt(moveX * moveX + moveZ * moveZ);
        if (length > 0) {
            moveX /= length;
            moveZ /= length;
        }

        // Apply movement (slow speed for precision)
        double speed = 0.1;
        mc.player.setVelocity(moveX * speed, mc.player.getVelocity().y, moveZ * speed);
    }

    private void walk() {
        if (walkTicks > 0) {
            walkTicks--;
            return;
        }

        // Safety check: if we've been walking too long, force stop
        if (maxWalkTicks <= 0) {
            mc.options.backKey.setPressed(false);
            state = State.WAITING;
            walkTicks = walkDelay.get();
            return;
        }
        maxWalkTicks--;

        if (targetPos == null) {
            mc.options.backKey.setPressed(false);
            state = State.WAITING;
            return;
        }

        // Calculate center of target block
        double targetX = targetPos.getX() + 0.5;
        double targetZ = targetPos.getZ() + 0.5;

        // Get player position
        double playerX = mc.player.getX();
        double playerZ = mc.player.getZ();

        // Check if player has reached target (with more generous tolerance)
        double tolerance = 0.3;
        if (Math.abs(playerX - targetX) < tolerance && Math.abs(playerZ - targetZ) < tolerance) {
            // Player has reached target
            mc.options.backKey.setPressed(false);
            state = State.WAITING;
            walkTicks = walkDelay.get();
            return;
        }

        // Press S key to walk backward
        mc.options.backKey.setPressed(true);
    }

    private void wait_state() {
        if (walkTicks > 0) {
            walkTicks--;
            return;
        }

        // Switch patterns and restart building continuously
        buildStep = 0;

        if (usePattern2.get()) {
            usingPattern1 = !usingPattern1;
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

        return !mc.world.getBlockState(pos).isReplaceable();
    }
}
