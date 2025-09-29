package xbwk.addon.modules;

import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.text.Text;
import xbwk.addon.Elytraskyaddon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;

public class Botmode extends Module {
    public enum CommandType {
        W("/w"),
        TELL("/tell"),
        MSG("/msg"),
        WHISPER("/whisper");

        private final String command;

        CommandType(String command) {
            this.command = command;
        }

        @Override
        public String toString() {
            return command;
        }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTiming = settings.createGroup("Timing Settings");
    private final SettingGroup sgLogSettings = settings.createGroup("Log Settings");
    private final SettingGroup sgMessageSettings = settings.createGroup("Message Settings");

    // Master player setting
    private final Setting<String> masterPlayer = sgGeneral.add(new StringSetting.Builder()
            .name("master-player")
            .description("Player who can control this bot")
            .defaultValue("")
            .build()
    );

    // Command type setting
    private final Setting<CommandType> commandType = sgGeneral.add(new EnumSetting.Builder<CommandType>()
            .name("command-type")
            .description("Type of private message command to use")
            .defaultValue(CommandType.MSG)
            .build()
    );

    // Message delay setting
    private final Setting<Integer> messageDelay = sgMessageSettings.add(new IntSetting.Builder()
            .name("message-delay")
            .description("Delay between private messages (ticks, 20 ticks = 1 second)")
            .defaultValue(20)
            .min(0)
            .sliderRange(0, 100)
            .build()
    );

    // Log directory setting
    private final Setting<String> logDirectory = sgLogSettings.add(new StringSetting.Builder()
            .name("log-directory")
            .description("Directory where base logs are stored")
            .defaultValue("base_finder_logs")
            .build()
    );

    // Timing settings
    private final Setting<Integer> baseInfoDelay = sgTiming.add(new IntSetting.Builder()
            .name("base-info-delay")
            .description("Delay between sending base info messages (seconds)")
            .defaultValue(3)
            .min(1)
            .sliderRange(1, 10)
            .build()
    );

    private final Setting<Integer> statusUpdateDelay = sgTiming.add(new IntSetting.Builder()
            .name("status-update-delay")
            .description("Delay between status updates (seconds)")
            .defaultValue(2)
            .min(1)
            .sliderRange(1, 10)
            .build()
    );

    // Runtime variables
    private final List<String> baseLogEntries = new ArrayList<>();
    private final Queue<String> messageQueue = new LinkedList<>();
    private final Set<String> processedMessages = new HashSet<>();
    private int statusTimer = 0;
    private int baseInfoIndex = 0;
    private int baseInfoTimer = 0;
    private int messageTimer = 0;
    private long startTime;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");
    private final SimpleDateFormat fileDateFormat = new SimpleDateFormat("yyyy-MM-dd");

    // 添加对Advertise模块的引用
    private xbwk.addon.modules.Advertise advertiseModule;

    // 支持的命令列表
    private static final Set<String> SUPPORTED_COMMANDS = Set.of(
            "=checkelytra",
            "=base",
            "=status",
            "=readlogs",
            "=adon",
            "=adoff"
    );

    public Botmode() {
        super(Elytraskyaddon.CATEGORY, "bot-mode", "Follows commands from specified player");
    }

    @Override
    public void onActivate() {
        startTime = System.currentTimeMillis();
        statusTimer = 0;
        baseInfoIndex = 0;
        baseInfoTimer = 0;
        messageTimer = 0;
        baseLogEntries.clear();
        messageQueue.clear();
        processedMessages.clear();

        // 获取Advertise模块引用
        advertiseModule = Modules.get().get(xbwk.addon.modules.Advertise.class);

        info("Botmode enabled. The owner: " + masterPlayer.get());
    }

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        if (mc.player == null || masterPlayer.get().isEmpty()) return;

        String message = event.getMessage().getString();
        String messageHash = Integer.toHexString(message.hashCode());

        // 检查是否是Bot自己发送的消息，避免无限循环
        if (isMessageFromBot(message)) {
            return;
        }

        // 检查是否已经处理过这条消息，避免重复处理
        if (processedMessages.contains(messageHash)) {
            return;
        }

        // 检查是否是主人玩家发送的消息
        if (isMessageFromMasterPlayer(message)) {
            // 标记消息为已处理
            processedMessages.add(messageHash);

            // 清理过期的已处理消息（防止内存泄漏）
            if (processedMessages.size() > 100) {
                processedMessages.clear();
            }

            if (message.contains("=")) {
                // 提取命令部分（从=开始到空格或结尾）
                String commandPart = message.substring(message.indexOf("="));
                String command = commandPart.split(" ")[0].toLowerCase().trim();

                // 在公屏显示处理信息
                ChatUtils.sendPlayerMsg("正在处理信息");

                // 验证命令是否有效
                if (!isValidCommand(command)) {
                    queuePrivateMessage("未知命令: " + command + "，支持的命令: " + String.join(", ", SUPPORTED_COMMANDS));
                    return;
                }

                switch (command) {
                    case "=checkelytra":
                        handleElytraCheck();
                        break;
                    case "=base":
                        handleBaseInfo();
                        break;
                    case "=status":
                        startStatusUpdates();
                        break;
                    case "=readlogs":
                        readBaseLogs();
                        break;
                    case "=adon":
                        handleAdvertiseOn();
                        break;
                    case "=adoff":
                        handleAdvertiseOff();
                        break;
                }

                // 发送命令接受确认
                queuePrivateMessage("接受命令: " + command);
            }
        }
    }

    // 检查命令是否有效
    private boolean isValidCommand(String command) {
        return SUPPORTED_COMMANDS.contains(command);
    }

    // 检查消息是否来自Bot自己
    private boolean isMessageFromBot(String message) {
        // 检查是否是Meteor客户端的消息或者是Bot自己发送的消息
        return message.contains("[Meteor]") ||
                message.contains("[Bot Mode]") ||
                message.contains(mc.player.getName().getString() + ":") ||
                message.contains("<" + mc.player.getName().getString() + ">");
    }

    // 检查消息是否来自主人玩家
    private boolean isMessageFromMasterPlayer(String message) {
        if (masterPlayer.get().isEmpty()) return false;

        String master = masterPlayer.get();

        // 检查各种可能的聊天格式
        if (message.contains("<" + master + ">")) {
            return true;
        }

        if (message.contains(master + ":")) {
            return true;
        }

        if (message.contains("[") && message.contains("]") && message.contains(master)) {
            return true;
        }

        // 检查私聊消息格式
        if (message.contains("whispers:") && message.contains(master)) {
            return true;
        }

        if (message.contains("tells you:") && message.contains(master)) {
            return true;
        }

        // 更简单的检查：消息中包含主人玩家名
        if (message.contains(master)) {
            // 进一步验证这是发送者而不是消息内容
            if (message.startsWith(master) ||
                    message.contains("<" + master) ||
                    message.contains(master + ">") ||
                    message.contains(master + ":")) {
                return true;
            }
        }

        return false;
    }

    private void handleAdvertiseOn() {
        if (advertiseModule != null) {
            if (!advertiseModule.isActive()) {
                advertiseModule.toggle();
                queuePrivateMessage("广告模块已启用");
            } else {
                queuePrivateMessage("广告模块已经在运行中");
            }
        } else {
            queuePrivateMessage("无法找到广告模块");
        }
    }

    private void handleAdvertiseOff() {
        if (advertiseModule != null) {
            if (advertiseModule.isActive()) {
                advertiseModule.toggle();
                queuePrivateMessage("广告模块已禁用");
            } else {
                queuePrivateMessage("广告模块已经处于关闭状态");
            }
        } else {
            queuePrivateMessage("无法找到广告模块");
        }
    }

    // 将消息加入队列，等待延迟发送
    private void queuePrivateMessage(String message) {
        messageQueue.add(message);
    }

    // 立即发送私信（不经过队列）
    private void sendPrivateMessageImmediately(String message) {
        String command = String.format("%s %s %s",
                commandType.get().toString(),
                masterPlayer.get(),
                message);

        ChatUtils.sendPlayerMsg(command);
    }

    private void handleElytraCheck() {
        queuePrivateMessage("正在检查鞘翅...");

        int totalElytra = 0;
        int fullDurability = 0;
        int currentDurability = 0;

        // Check inventory for elytra
        for (int i = 0; i < mc.player.getInventory().size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.getItem() == Items.ELYTRA) {
                totalElytra++;
                if (stack.getDamage() == 0) {
                    fullDurability++;
                }
                currentDurability = stack.getMaxDamage() - stack.getDamage();
            }
        }

        // Send private message with results
        String privateMessage = String.format("鞘翅数量: %d | 满耐久: %d | 当前耐久: %d",
                totalElytra, fullDurability, currentDurability);
        queuePrivateMessage(privateMessage);
    }

    private void handleBaseInfo() {
        readBaseLogs();
        if (baseLogEntries.isEmpty()) {
            queuePrivateMessage("未发现任何基地日志");
            return;
        }

        queuePrivateMessage("开始发送基地日志信息 (" + baseLogEntries.size() + " 条记录)");
        baseInfoIndex = 0;
        baseInfoTimer = 1; // Start sending on next tick
    }

    private void readBaseLogs() {
        baseLogEntries.clear();

        try {
            String meteorDir = System.getProperty("user.dir");
            Path logDirPath = Paths.get(meteorDir, logDirectory.get());

            if (!Files.exists(logDirPath)) {
                queuePrivateMessage("日志目录不存在: " + logDirPath);
                return;
            }

            // 获取所有日志文件，按日期排序（最新的优先）
            List<Path> logFiles = new ArrayList<>();
            Files.list(logDirPath)
                    .filter(path -> path.toString().endsWith(".csv") && path.getFileName().toString().startsWith("bases_"))
                    .sorted((p1, p2) -> {
                        try {
                            return Files.getLastModifiedTime(p2).compareTo(Files.getLastModifiedTime(p1));
                        } catch (IOException e) {
                            return 0;
                        }
                    })
                    .forEach(logFiles::add);

            if (logFiles.isEmpty()) {
                queuePrivateMessage("未找到任何日志文件");
                return;
            }

            // 读取所有日志文件的内容
            for (Path logFile : logFiles) {
                try {
                    List<String> lines = Files.readAllLines(logFile);

                    // 跳过标题行并处理每个条目
                    for (int i = 1; i < lines.size(); i++) {
                        String line = lines.get(i);
                        if (!line.trim().isEmpty()) {
                            baseLogEntries.add(line);
                        }
                    }

                } catch (IOException e) {
                    error("读取日志文件时出错: " + logFile.getFileName() + " - " + e.getMessage());
                }
            }

            queuePrivateMessage("成功读取 " + baseLogEntries.size() + " 条基地日志记录");

        } catch (IOException e) {
            queuePrivateMessage("读取日志目录时出错: " + e.getMessage());
            error("Failed to read log directory: " + e.getMessage());
        }
    }

    private void startStatusUpdates() {
        statusTimer = 1; // Start sending on next tick
        queuePrivateMessage("开始状态更新");
    }

    private void sendStatusUpdate() {
        // 检查Advertise模块状态
        boolean isAdvertising = advertiseModule != null && advertiseModule.isActive();

        String status = String.format("在线时长: %s | 生命值: %.1f | 状态: %s | 广告: %s | 日志记录: %d",
                getUptime(),
                mc.player.getHealth(),
                getCurrentStatus(),
                isAdvertising ? "开启" : "关闭",
                baseLogEntries.size());

        queuePrivateMessage(status);
    }

    private void sendBaseLogEntry(String logEntry) {
        // Parse CSV format: Time,Base ID,X,Y,Z,Block Count,Volume,Density,Block Types
        String[] parts = logEntry.split(",");
        if (parts.length >= 8) {
            try {
                String time = parts[0];
                String baseId = parts[1];
                int x = Integer.parseInt(parts[2]);
                int y = Integer.parseInt(parts[3]);
                int z = Integer.parseInt(parts[4]);
                int blockCount = Integer.parseInt(parts[5]);
                double volume = Double.parseDouble(parts[6]);
                double density = Double.parseDouble(parts[7]);

                String info = String.format("基地 #%d/%d | 时间: %s | 位置: %d %d %d | 方块: %d | 密度: %.4f",
                        baseInfoIndex + 1,
                        baseLogEntries.size(),
                        time,
                        x, y, z,
                        blockCount,
                        density);

                queuePrivateMessage(info);

            } catch (NumberFormatException e) {
                queuePrivateMessage("日志格式错误: " + logEntry);
            }
        } else {
            queuePrivateMessage("无效日志条目: " + logEntry);
        }
    }

    private String getUptime() {
        long uptime = System.currentTimeMillis() - startTime;
        long hours = uptime / (1000 * 60 * 60);
        long minutes = (uptime % (1000 * 60 * 60)) / (1000 * 60);
        return String.format("%d小时 %d分钟", hours, minutes);
    }

    private String getCurrentStatus() {
        if (statusTimer > 0) {
            return "状态更新";
        } else if (baseInfoTimer > 0) {
            return "发送基地信息";
        } else {
            return "待命";
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive()) return;

        // Handle message queue with delay
        if (messageDelay.get() > 0 && !messageQueue.isEmpty()) {
            messageTimer++;
            if (messageTimer >= messageDelay.get()) {
                sendNextQueuedMessage();
                messageTimer = 0;
            }
        } else if (messageDelay.get() == 0) {
            // 如果延迟为0，立即发送所有队列中的消息
            while (!messageQueue.isEmpty()) {
                sendNextQueuedMessage();
            }
        }

        // Handle status update timer
        if (statusTimer > 0) {
            statusTimer--;
            if (statusTimer == 0) {
                sendStatusUpdate();
                statusTimer = statusUpdateDelay.get() * 20;
            }
        }

        // Handle base info timer
        if (baseInfoTimer > 0) {
            baseInfoTimer--;
            if (baseInfoTimer == 0 && baseInfoIndex < baseLogEntries.size()) {
                sendBaseLogEntry(baseLogEntries.get(baseInfoIndex));
                baseInfoIndex++;
                baseInfoTimer = baseInfoDelay.get() * 20;
            }
        }
    }

    // 发送队列中的下一条消息
    private void sendNextQueuedMessage() {
        if (!messageQueue.isEmpty()) {
            String message = messageQueue.poll();
            sendPrivateMessageImmediately(message);
        }
    }
}