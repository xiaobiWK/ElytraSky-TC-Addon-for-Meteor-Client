package xbwk.addon.modules;

import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import xbwk.addon.Elytraskyaddon;
import xbwk.addon.utils.WorldScanner;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class BaseFinderPlus extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgLogging = settings.createGroup("Logging Settings");
    private final SettingGroup sgAdvanced = settings.createGroup("Advanced Settings");

    // Scan settings
    private final Setting<Integer> scanRadius = sgGeneral.add(new IntSetting.Builder()
            .name("scan-radius")
            .description("The radius to scan for valuable blocks.")
            .defaultValue(64)
            .min(16)
            .sliderMax(256)
            .build()
    );

    private final Setting<Integer> scanInterval = sgGeneral.add(new IntSetting.Builder()
            .name("scan-interval")
            .description("The interval in ticks between scans.")
            .defaultValue(20)
            .min(1)
            .sliderMax(100)
            .build()
    );

    private final Setting<Integer> blockDetectionThreshold = sgGeneral.add(new IntSetting.Builder()
            .name("block-threshold")
            .description("The number of valuable blocks to find before a stash is detected.")
            .defaultValue(5)
            .min(1)
            .sliderMax(50)
            .build()
    );

    // Target blocks to scan for
    private final Setting<List<Block>> targetBlocks = sgGeneral.add(new BlockListSetting.Builder()
            .name("target-blocks")
            .description("Blocks to scan for")
            .defaultValue(Arrays.asList(
                    Blocks.CHEST,
                    Blocks.BARREL,
                    Blocks.SHULKER_BOX,
                    Blocks.TRAPPED_CHEST,
                    Blocks.ENDER_CHEST
            ))
            .build()
    );

    // Advanced settings
    private final Setting<Boolean> storageOnlyMode = sgAdvanced.add(new BoolSetting.Builder()
            .name("storage-only-mode")
            .description("Only search for storage containers (chests, shulkers). Disable to search for all stash blocks.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Integer> maxVolumeThreshold = sgAdvanced.add(new IntSetting.Builder()
            .name("max-volume-threshold")
            .description("Maximum volume to prevent natural structure detection.")
            .defaultValue(50000)
            .min(0)
            .sliderMax(100000)
            .build()
    );

    private final Setting<Boolean> filterNaturalStructures = sgAdvanced.add(new BoolSetting.Builder()
            .name("filter-natural-structures")
            .description("Filter out likely natural structures (dungeons, etc.).")
            .defaultValue(true)
            .build()
    );

    private final Setting<Double> minDensityThreshold = sgAdvanced.add(new DoubleSetting.Builder()
            .name("min-density-threshold")
            .description("Minimum density required for stash detection.")
            .defaultValue(0.05)
            .min(0.0001)
            .sliderMax(0.1)
            .build()
    );

    private final Setting<Double> notificationDensityThreshold = sgAdvanced.add(new DoubleSetting.Builder()
            .name("notification-density-threshold")
            .description("Minimum density required to send notifications. Set to 0 to notify for all detected stashes.")
            .defaultValue(0.01)
            .min(0.0)
            .sliderMax(0.1)
            .build()
    );

    private final Setting<Integer> maxClusterDistance = sgAdvanced.add(new IntSetting.Builder()
            .name("max-cluster-distance")
            .description("Maximum distance between blocks in a cluster.")
            .defaultValue(50)
            .min(10)
            .sliderMax(200)
            .build()
    );

    private final Setting<Integer> cacheDuration = sgAdvanced.add(new IntSetting.Builder()
            .name("cache-duration")
            .description("Duration to cache scanned chunks (minutes)")
            .defaultValue(30)
            .min(5)
            .sliderMax(120)
            .build()
    );

    // Logging settings
    private final Setting<Boolean> enableLogging = sgGeneral.add(new BoolSetting.Builder()
            .name("enable-logging")
            .description("Enable logging to file")
            .defaultValue(true)
            .build()
    );

    private final Setting<String> logDirName = sgGeneral.add(new StringSetting.Builder()
            .name("log-directory")
            .description("Directory to save log files")
            .defaultValue("base_finder_logs")
            .visible(enableLogging::get)
            .build()
    );

    private final Setting<Boolean> logDetailedInfo = sgLogging.add(new BoolSetting.Builder()
            .name("log-detailed-info")
            .description("Log detailed information (density, block stats, etc.)")
            .defaultValue(true)
            .visible(enableLogging::get)
            .build()
    );

    // Sound settings
    private final Setting<Boolean> enableSoundAlert = sgGeneral.add(new BoolSetting.Builder()
            .name("enable-sound-alert")
            .description("Play sound when base is detected")
            .defaultValue(true)
            .build()
    );

    private final Setting<Double> soundVolume = sgGeneral.add(new DoubleSetting.Builder()
            .name("sound-volume")
            .description("Volume of the alert sound")
            .defaultValue(1.0)
            .min(0.0)
            .max(2.0)
            .sliderMax(2.0)
            .visible(enableSoundAlert::get)
            .build()
    );

    // 状态变量
    private int tickCounter = 0;
    private final Map<ChunkPos, Long> scannedChunks = new ConcurrentHashMap<>();
    private final List<BlockPos> foundBlocks = new ArrayList<>();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private final SimpleDateFormat fileDateFormat = new SimpleDateFormat("yyyy-MM-dd");
    private final SimpleDateFormat logDateFormat = new SimpleDateFormat("HH:mm:ss");
    private Path logDirectory;
    private Path currentLogFile;
    private final List<BlockPos> reportedStashes = new ArrayList<>();

    public BaseFinderPlus() {
        super(Elytraskyaddon.CATEGORY, "base-finder-plus", "Advanced base finder using WorldScanner utilities");
    }

    @Override
    public void onActivate() {
        scannedChunks.clear();
        foundBlocks.clear();
        reportedStashes.clear();
        initializeLogDirectory();
        info("BaseFinderPlus enabled");
    }

    @Override
    public void onDeactivate() {
        info("BaseFinderPlus disabled. Scanned " + scannedChunks.size() + " chunks total");
    }

    private void initializeLogDirectory() {
        try {
            String meteorDir = System.getProperty("user.dir");
            logDirectory = Paths.get(meteorDir, logDirName.get());

            if (!Files.exists(logDirectory)) {
                Files.createDirectories(logDirectory);
                info("Created log directory: " + logDirectory);
            }

            createNewLogFile();
        } catch (IOException e) {
            error("Failed to initialize log directory: " + e.getMessage());
        }
    }

    private void createNewLogFile() throws IOException {
        String dateStr = fileDateFormat.format(new Date());
        String fileName = "bases_" + dateStr + ".csv";
        currentLogFile = logDirectory.resolve(fileName);

        if (!Files.exists(currentLogFile)) {
            String header = "Time,Base ID,X,Y,Z,Block Count,Volume,Density,Block Types\n";
            Files.write(currentLogFile, header.getBytes(), StandardOpenOption.CREATE);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        tickCounter++;

        // Scan at specified interval
        if (tickCounter % scanInterval.get() == 0) {
            scanWorld();
        }

        // Clean up old scanned chunks
        if (tickCounter % 6000 == 0) {
            cleanupScannedChunks();
        }
    }

    private void scanWorld() {
        BlockPos playerPos = mc.player.getBlockPos();
        int radius = scanRadius.get();
        int chunkRadius = (radius / 16) + 1;
        ChunkPos playerChunk = new ChunkPos(playerPos);

        int blocksFoundThisScan = 0;

        for (int x = -chunkRadius; x <= chunkRadius; x++) {
            for (int z = -chunkRadius; z <= chunkRadius; z++) {
                ChunkPos chunkPos = new ChunkPos(playerChunk.x + x, playerChunk.z + z);

                if (isChunkCached(chunkPos)) {
                    continue;
                }

                blocksFoundThisScan += scanChunk(chunkPos, playerPos);
                scannedChunks.put(chunkPos, System.currentTimeMillis());
            }
        }

        if (blocksFoundThisScan > 0) {
            processFoundBlocksWithClustering();
        }
    }

    private boolean isChunkCached(ChunkPos chunkPos) {
        if (!scannedChunks.containsKey(chunkPos)) {
            return false;
        }
        long cacheTime = scannedChunks.get(chunkPos);
        return System.currentTimeMillis() - cacheTime < cacheDuration.get() * 60 * 1000;
    }

    private int scanChunk(ChunkPos chunkPos, BlockPos playerPos) {
        Chunk chunk = mc.world.getChunk(chunkPos.x, chunkPos.z);
        if (chunk == null || chunk instanceof EmptyChunk) {
            return 0;
        }

        int blocksFound = 0;
        List<Block> targetBlockList = targetBlocks.get();

        // Scan block entities first
        for (BlockPos pos : chunk.getBlockEntityPositions()) {
            if (pos.isWithinDistance(playerPos, scanRadius.get())) {
                try {
                    Block block = mc.world.getBlockState(pos).getBlock();
                    if (targetBlockList.contains(block)) {
                        foundBlocks.add(pos.toImmutable());
                        blocksFound++;
                    }
                } catch (Exception e) {
                    // Ignore chunk access errors
                }
            }
        }

        // Full block scanning if not in storage-only mode
        if (!storageOnlyMode.get()) {
            int limitedScanRadius = Math.min(scanRadius.get(), 32);

            for (int x = chunk.getPos().getStartX(); x <= chunk.getPos().getEndX(); x += 2) {
                for (int z = chunk.getPos().getStartZ(); z <= chunk.getPos().getEndZ(); z += 2) {
                    if (Math.sqrt(Math.pow(x - playerPos.getX(), 2) + Math.pow(z - playerPos.getZ(), 2)) > limitedScanRadius) {
                        continue;
                    }

                    int minY = Math.max(mc.world.getBottomY(), -64);
                    int maxY = Math.min(mc.world.getHeight(), 80);

                    for (int y = minY; y < maxY; y += 2) {
                        BlockPos pos = new BlockPos(x, y, z);
                        if (pos.isWithinDistance(playerPos, limitedScanRadius)) {
                            try {
                                Block block = mc.world.getBlockState(pos).getBlock();
                                if (targetBlockList.contains(block)) {
                                    foundBlocks.add(pos.toImmutable());
                                    blocksFound++;
                                }
                            } catch (Exception e) {
                                // Ignore errors when accessing block states
                            }
                        }
                    }
                }
            }
        }

        return blocksFound;
    }

    private void processFoundBlocksWithClustering() {
        if (foundBlocks.isEmpty()) return;

        List<List<BlockPos>> clusters = clusterBlocks(foundBlocks, maxClusterDistance.get());

        for (List<BlockPos> cluster : clusters) {
            if (cluster.size() >= blockDetectionThreshold.get()) {
                processCluster(cluster);
            }
        }

        foundBlocks.clear();
    }

    private List<List<BlockPos>> clusterBlocks(List<BlockPos> blocks, int maxDistance) {
        List<List<BlockPos>> clusters = new ArrayList<>();
        List<BlockPos> unprocessed = new ArrayList<>(blocks);

        while (!unprocessed.isEmpty()) {
            BlockPos seed = unprocessed.remove(0);
            List<BlockPos> cluster = new ArrayList<>();
            cluster.add(seed);

            boolean foundNew = true;
            while (foundNew) {
                foundNew = false;
                List<BlockPos> toRemove = new ArrayList<>();

                for (BlockPos unprocessedBlock : unprocessed) {
                    for (BlockPos clusterBlock : cluster) {
                        if (unprocessedBlock.isWithinDistance(clusterBlock, maxDistance)) {
                            cluster.add(unprocessedBlock);
                            toRemove.add(unprocessedBlock);
                            foundNew = true;
                            break;
                        }
                    }
                }

                unprocessed.removeAll(toRemove);
            }

            clusters.add(cluster);
        }

        return clusters;
    }

    private void processCluster(List<BlockPos> cluster) {
        BlockPos center = calculateClusterCenter(cluster);
        double volume = WorldScanner.getBoundingBoxVolume(cluster);
        double density = cluster.size() / Math.max(volume, 1.0);

        // Check if we've already reported a base near this location
        boolean alreadyReported = reportedStashes.stream()
                .anyMatch(reportedStash -> reportedStash.isWithinDistance(center, 100));

        if (alreadyReported) {
            return;
        }

        // Enhanced filtering for natural structures
        if (isNaturalStructure(cluster, volume, density)) {
            return;
        }

        reportedStashes.add(center);
        reportStash(center, cluster, volume, density);
    }

    private boolean isNaturalStructure(List<BlockPos> blocks, double volume, double density) {
        if (!filterNaturalStructures.get()) {
            return false;
        }

        // Volume check - natural structures tend to be very large
        if (volume > maxVolumeThreshold.get()) {
            return true;
        }

        // Density check - natural structures have very low density
        if (density < minDensityThreshold.get()) {
            return true;
        }

        // Count block types to identify natural structures
        Map<Block, Integer> blockCounts = countBlockTypes(blocks);
        int totalBlocks = blocks.size();
        int chestCount = blockCounts.getOrDefault(Blocks.CHEST, 0);

        // Check for Woodland Mansion: 44 chests and mostly wood/cobblestone
        if (chestCount == 44) {
            int darkOakLogCount = blockCounts.getOrDefault(Blocks.DARK_OAK_LOG, 0);
            int darkOakPlanksCount = blockCounts.getOrDefault(Blocks.DARK_OAK_PLANKS, 0);
            int cobblestoneCount = blockCounts.getOrDefault(Blocks.COBBLESTONE, 0);
            double mansionBlockRatio = (double)(darkOakLogCount + darkOakPlanksCount + cobblestoneCount) / totalBlocks;

            if (mansionBlockRatio > 0.5) {
                return true; // Likely a Woodland Mansion
            }
        }

        return false;
    }

    private Map<Block, Integer> countBlockTypes(List<BlockPos> blocks) {
        Map<Block, Integer> counts = new HashMap<>();
        for (BlockPos pos : blocks) {
            try {
                Block block = mc.world.getBlockState(pos).getBlock();
                counts.put(block, counts.getOrDefault(block, 0) + 1);
            } catch (Exception e) {
                // Ignore errors when accessing block states
            }
        }
        return counts;
    }

    private BlockPos calculateClusterCenter(List<BlockPos> blocks) {
        if (blocks.isEmpty()) return mc.player.getBlockPos();

        int totalX = 0, totalY = 0, totalZ = 0;
        for (BlockPos pos : blocks) {
            totalX += pos.getX();
            totalY += pos.getY();
            totalZ += pos.getZ();
        }

        return new BlockPos(
                totalX / blocks.size(),
                totalY / blocks.size(),
                totalZ / blocks.size()
        );
    }

    private void reportStash(BlockPos stashPos, List<BlockPos> valuableBlocks, double volume, double density) {
        String coords = stashPos.toShortString();
        String rating = getRatingFromDensity(density);

        Map<Block, Integer> counts = countBlockTypes(valuableBlocks);
        StringBuilder containerList = new StringBuilder();
        counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .forEach(entry -> containerList
                        .append(entry.getValue())
                        .append("x ")
                        .append(entry.getKey().getName().getString())
                        .append("\n"));

        String modeInfo = storageOnlyMode.get() ? " (Storage Only)" : " (Full Stash)";
        String logMessage = String.format("%s at: %s with %d valuable blocks (density: %.6f)",
                storageOnlyMode.get() ? "Stash Found" : "Stash Found", coords, valuableBlocks.size(), density);

        // Always log to console/chat
        info(logMessage);

        // Only send notification if density meets threshold
        if (density >= notificationDensityThreshold.get()) {
            String description = String.format(
                    "Coordinates: %s%s\n" +
                            "Found %d valuable blocks\n" +
                            "Volume: %.2f blocks\n" +
                            "Density: %.6f\n" +
                            "Rating: %s\n\n" +
                            "Container List:\n%s",
                    coords, modeInfo, valuableBlocks.size(), volume, density, rating, containerList.toString()
            );

            // Log to file
            if (enableLogging.get()) {
                logBase(stashPos, valuableBlocks.size(), volume, density, counts);
            }

            // Play sound alert
            if (enableSoundAlert.get() && mc.player != null) {
                mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
                        SoundCategory.MASTER,
                        soundVolume.get().floatValue(),
                        1.0f);
            }
        } else {
            // Log that we skipped notification due to low density
            info("Skipped notification for low density stash (density: " + String.format("%.6f", density) +
                    ", threshold: " + String.format("%.6f", notificationDensityThreshold.get()) + ")");
        }
    }

    private String getRatingFromDensity(double density) {
        if (density > 0.1) return "Very High Density";
        if (density > 0.01) return "High Density";
        if (density > 0.005) return "Medium Density";
        if (density > 0.001) return "Low Density";
        return "Very Low Density";
    }

    private void logBase(BlockPos center, int blockCount, double volume, double density, Map<Block, Integer> blockCounts) {
        try {
            // Check if we need to create a new log file for the new day
            String currentDate = fileDateFormat.format(new Date());
            if (!currentLogFile.toString().contains(currentDate)) {
                createNewLogFile();
            }

            String timeStr = logDateFormat.format(new Date());
            String baseId = "base_" + center.getX() + "_" + center.getY() + "_" + center.getZ();
            String blockTypes = WorldScanner.createBlockSummary(blockCounts).replace(",", ";");

            String logEntry = String.format("%s,%s,%d,%d,%d,%d,%.2f,%.6f,%s\n",
                    timeStr,
                    baseId,
                    center.getX(),
                    center.getY(),
                    center.getZ(),
                    blockCount,
                    volume,
                    density,
                    blockTypes
            );

            Files.write(currentLogFile, logEntry.getBytes(), StandardOpenOption.APPEND);
        } catch (IOException e) {
            error("Failed to write to log file: " + e.getMessage());
        }
    }

    private void cleanupScannedChunks() {
        long currentTime = System.currentTimeMillis();
        long expireTime = currentTime - (cacheDuration.get() * 60 * 1000);

        scannedChunks.entrySet().removeIf(entry ->
                entry.getValue() < expireTime
        );

        info("Cleaned up scanned chunks. Currently tracking " + scannedChunks.size() + " chunks");
    }

    public List<BlockPos> getFoundBlocks() {
        return new ArrayList<>(foundBlocks);
    }

    public void clearFoundBlocks() {
        foundBlocks.clear();
        info("Cleared found blocks list");
    }

    public int getScannedChunkCount() {
        return scannedChunks.size();
    }

    public int getReportedBaseCount() {
        return reportedStashes.size();
    }

    public void clearReportedBases() {
        reportedStashes.clear();
        info("Cleared all reported bases");
    }

    public void forceScan() {
        scanWorld();
        info("Manual scan completed");
    }
}