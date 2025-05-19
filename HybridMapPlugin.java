// HybridMapPlugin.java
package cn.nukkit.plugin;

import cn.nukkit.*;
import cn.nukkit.block.Block;
import cn.nukkit.entity.Entity;
import cn.nukkit.event.*;
import cn.nukkit.event.player.*;
import cn.nukkit.level.Level;
import cn.nukkit.math.Vector3;
import cn.nukkit.nbt.tag.CompoundTag;
import cn.nukkit.network.protocol.*;
import cn.nukkit.scheduler.Task;
import cn.nukkit.utils.BossBar;
import cn.nukkit.utils.Config;

import java.util.*;
import java.util.concurrent.*;

public class HybridMapPlugin extends PluginBase implements Listener {

    // 核心数据结构
    private final Map<Player, MapUIState> playerUIStates = new ConcurrentHashMap<>();
    private final Map<Player, BossBar> bossBars = new ConcurrentHashMap<>();
    private Config config;
    private TeleportManager teleportManager;
    private MapRenderer mapRenderer;
    private PerformanceMonitor performanceMonitor;

    // 任务实例
    private BossBarUpdateTask bossBarUpdateTask;
    private MapUpdateTask mapUpdateTask;

    @Override
    public void onEnable() {
        // 初始化配置
        this.saveResource("config.yml", false);
        this.config = new Config(this.getDataFolder() + "/config.yml", Config.YAML);
        initDefaultConfig();

        // 初始化子系统
        this.teleportManager = new TeleportManager();
        this.mapRenderer = new MapRenderer();
        this.performanceMonitor = new PerformanceMonitor();

        // 注册事件监听
        getServer().getPluginManager().registerEvents(this, this);

        // 启动任务
        startScheduledTasks();
    }

    private void initDefaultConfig() {
        config.addDefault("bossbar_enabled", true);
        config.addDefault("bossbar_refresh_rate", 20);
        config.addDefault("map_icon_refresh_rate", 5);
        config.addDefault("teleport_cooldown", 300);
        config.addDefault("max_cpu_usage", 70);
        config.addDefault("max_memory_usage", 80);
        config.save();
    }

    private void startScheduledTasks() {
        int mapInterval = config.getInt("map_icon_refresh_rate", 5);
        this.mapUpdateTask = new MapUpdateTask();
        getServer().getScheduler().scheduleRepeatingTask(this, mapUpdateTask, mapInterval, true);

        int bossbarInterval = config.getInt("bossbar_refresh_rate", 20);
        this.bossBarUpdateTask = new BossBarUpdateTask();
        getServer().getScheduler().scheduleRepeatingTask(this, bossBarUpdateTask, bossbarInterval, true);

        getServer().getScheduler().scheduleRepeatingTask(this, new PerformanceMonitorTask(), 100, true);
    }

    // 事件处理
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        initializePlayerUI(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        playerUIStates.remove(player);
        bossBars.remove(player);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        MapUIState state = playerUIStates.get(player);
        if (state == null) return;

        if (event.getAction() == PlayerInteractEvent.Action.RIGHT_CLICK_AIR) {
            state.lastClickTime = System.currentTimeMillis();
            if (state.isMapIconClicked()) {
                mapRenderer.toggleFullScreenMap(player);
            }
        }
    }

    // 玩家UI初始化
    private void initializePlayerUI(Player player) {
        MapUIState state = new MapUIState();
        playerUIStates.put(player, state);

        if (config.getBoolean("bossbar_enabled")) {
            BossBar bossBar = new BossBar.Builder(player)
                .text("定位中...")
                .healthPercent(1.0f)
                .build();
            bossBars.put(player, bossBar);
        }

        updateMapDisplay(player);
    }

    // 地图显示更新
    private void updateMapDisplay(Player player) {
        Vector3 pos = player.getPosition();
        ClientboundMapItemDataPacket packet = new ClientboundMapItemDataPacket();
        packet.mapId = generateMapId(player, pos);
        packet.update = 1;
        packet.scale = 1;
        packet.decorations = Collections.singletonList(
            new ClientboundMapItemDataPacket.MapDecoration(
                MapDecoration.Type.RED_X,
                (byte) (pos.getFloorX() >> 10),
                (byte) (pos.getFloorZ() >> 10),
                "当前位置"
            )
        );
        player.dataPacket(packet);
    }

    private long generateMapId(Player player, Vector3 pos) {
        return ((long) player.getId() << 48) | 
              ((pos.getFloorX() & 0xFFFF) << 32) | 
              ((pos.getFloorZ() & 0xFFFF) << 16) | 
              (System.currentTimeMillis() & 0xFFFF);
    }

    // 嵌套类定义
    private class MapUIState {
        long lastHoverTime;
        long lastClickTime;
        boolean fullScreenMapOpen;

        boolean isMapIconClicked() {
            return System.currentTimeMillis() - lastClickTime < 200;
        }
    }

    private class MapRenderer {
        void toggleFullScreenMap(Player player) {
            MapUIState state = playerUIStates.get(player);
            if (state == null) return;

            state.fullScreenMapOpen = !state.fullScreenMapOpen;
            if (state.fullScreenMapOpen) {
                openFullScreenMap(player);
            } else {
                closeFullScreenMap(player);
            }
        }

        private void openFullScreenMap(Player player) {
            player.sendTitle("§a全屏地图已开启");
            bossBars.computeIfPresent(player, (k, v) -> {
                v.setVisible(false);
                return v;
            });
        }

        private void closeFullScreenMap(Player player) {
            player.sendTitle("§e地图已关闭");
            bossBars.computeIfPresent(player, (k, v) -> {
                v.setVisible(true);
                return v;
            });
        }
    }

    private class TeleportManager {
        private final Map<Player, Long> cooldowns = new ConcurrentHashMap<>();

        void handleTeleport(Player player, Vector3 dest) {
            if (checkCooldown(player)) {
                Vector3 safeDest = findSafeLocation(dest);
                scheduleTeleport(player, safeDest);
            }
        }

        private boolean checkCooldown(Player player) {
            int cooldown = config.getInt("teleport_cooldown", 300);
            Long last = cooldowns.get(player);
            if (last != null && (System.currentTimeMillis() - last) < cooldown * 1000L) {
                player.sendActionBar("§c冷却中: " + (cooldown - (System.currentTimeMillis() - last)/1000) + "秒");
                return false;
            }
            cooldowns.put(player, System.currentTimeMillis());
            return true;
        }

        private Vector3 findSafeLocation(Vector3 dest) {
            Level level = dest.getLevel();
            int x = dest.getFloorX();
            int z = dest.getFloorZ();
            
            for (int y = dest.getFloorY(); y < level.getMaxHeight(); y++) {
                if (level.getBlockIdAt(x, y, z) == Block.AIR) {
                    return new Vector3(x, y, z);
                }
            }
            return dest;
        }

        private void scheduleTeleport(Player player, Vector3 dest) {
            getServer().getScheduler().scheduleDelayedTask(HybridMapPlugin.this, () -> {
                player.teleport(dest);
                player.sendActionBar("§e传送完成!");
            }, 60);
        }
    }

    // 定时任务
    private class MapUpdateTask extends Task {
        @Override
        public void onRun(int currentTick) {
            getServer().getOnlinePlayers().values().forEach(player -> {
                if (playerUIStates.containsKey(player)) {
                    updateMapDisplay(player);
                }
            });
        }
    }

    private class BossBarUpdateTask extends Task {
        @Override
        public void onRun(int currentTick) {
            getServer().getOnlinePlayers().forEach((uuid, player) -> {
                BossBar bar = bossBars.get(player);
                if (bar != null) {
                    Vector3 pos = player.getPosition();
                    String text = String.format("坐标: %d, %d, %d", 
                        pos.getFloorX(), pos.getFloorY(), pos.getFloorZ());
                    if (!bar.getText().equals(text)) {
                        bar.setText(text).update(player);
                    }
                }
            });
        }
    }

    private class PerformanceMonitorTask extends Task {
        @Override
        public void onRun(int currentTick) {
            performanceMonitor.checkAndAdjust();
        }
    }

    private class PerformanceMonitor {
        void checkAndAdjust() {
            adjustForHighCPU();
            adjustForLowMemory();
        }

        private void adjustForHighCPU() {
            int currentMapRate = config.getInt("map_icon_refresh_rate");
            int currentBossbarRate = config.getInt("bossbar_refresh_rate");
            
            if (currentMapRate < 40) {
                config.set("map_icon_refresh_rate", currentMapRate * 2);
                restartTask(mapUpdateTask, currentMapRate * 2);
            }
            
            if (currentBossbarRate < 80) {
                config.set("bossbar_refresh_rate", currentBossbarRate * 2);
                restartTask(bossBarUpdateTask, currentBossbarRate * 2);
            }
        }

        private void adjustForLowMemory() {
            getServer().getScheduler().scheduleDelayedTask(HybridMapPlugin.this, () -> {
                playerUIStates.keySet().removeIf(player -> !player.isOnline());
            }, 1);
        }

        private void restartTask(Task task, int newInterval) {
            getServer().getScheduler().cancelTask(task.getTaskId());
            getServer().getScheduler().scheduleRepeatingTask(
                HybridMapPlugin.this, task, newInterval, true);
        }
    }
}
