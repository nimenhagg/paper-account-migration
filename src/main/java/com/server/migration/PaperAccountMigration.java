package com.server.migration;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class PaperAccountMigration extends JavaPlugin implements CommandExecutor, TabCompleter, Listener {
    private MigrationAuditLogger auditLogger;
    private MigrationManager migrationManager;
    private DialogLoginManager dialogLoginManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.auditLogger = new MigrationAuditLogger(getDataFolder(), getLogger());
        this.migrationManager = new MigrationManager(this, auditLogger);
        this.dialogLoginManager = new DialogLoginManager(this);

        Bukkit.getPluginManager().registerEvents(dialogLoginManager, this);
        Bukkit.getPluginManager().registerEvents(this, this);

        if (getCommand("migrateaccount") != null) {
            getCommand("migrateaccount").setExecutor(this);
            getCommand("migrateaccount").setTabCompleter(this);
        }

        if (getCommand("migrationadmin") != null) {
            getCommand("migrationadmin").setExecutor(this);
            getCommand("migrationadmin").setTabCompleter(this);
        }

        getLogger().info("PaperAccountMigration enabled with strict command password masking and lockout management!");
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        String rawMessage = event.getMessage();
        if (rawMessage == null || rawMessage.trim().isEmpty()) {
            return;
        }

        String trimmed = rawMessage.trim();
        if (!trimmed.startsWith("/")) {
            return;
        }

        String[] parts = trimmed.substring(1).split("\\s+");
        if (parts.length == 0) {
            return;
        }

        String cmd = parts[0].toLowerCase(Locale.ROOT);
        if (cmd.equals("migrateaccount") || cmd.equals("migrate") || cmd.equals("accountmigrate")
                || cmd.equals("paperaccountmigration:migrateaccount")
                || cmd.equals("paperaccountmigration:migrate")
                || cmd.equals("paperaccountmigration:accountmigrate")) {

            // 1. Cancel Bukkit event immediately: CraftServer / Minecraft will NEVER log plaintext password!
            event.setCancelled(true);

            Player player = event.getPlayer();

            // 2. Safe masked logging for console and server log files (password masked as ******)
            if (parts.length > 2) {
                getLogger().info(player.getName() + " issued server command: /" + parts[0] + " " + parts[1] + " ******");
            } else if (parts.length == 2) {
                getLogger().info(player.getName() + " issued server command: /" + parts[0] + " " + parts[1]);
            } else {
                getLogger().info(player.getName() + " issued server command: /" + parts[0]);
            }

            // 3. Process migration arguments
            String[] args = new String[Math.max(0, parts.length - 1)];
            System.arraycopy(parts, 1, args, 0, args.length);

            handleMigrationExecution(player, parts[0], args);
        }
    }

    private void handleMigrationExecution(Player player, String label, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§6[账号迁移向导] §e用法: §f/" + label + " <旧游戏名字> <旧账号密码>");
            player.sendMessage("§7提示: 系统将自动验证旧密码并把背包物品、血量、经验、成就与登录数据一键平移至当前新名字！");
            return;
        }

        String oldName = args[0].trim();
        String oldPass = args[1];

        player.sendMessage("§e[账号迁移向导] 正在验证旧账号凭据并执行数据平移，请稍候...");

        // Run asynchronously to prevent server stall
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            MigrationResult result = migrationManager.performMigration(player, oldName, oldPass);

            Bukkit.getScheduler().runTask(this, () -> {
                if (result.isSuccess()) {
                    // Kick player to reload playerdata cleanly
                    StringBuilder sb = new StringBuilder();
                    sb.append("§a【账号数据迁移成功】\n\n");
                    sb.append("§f您已成功将旧账号 §e").append(oldName).append("§f 的数据继承至当前账号！\n");
                    sb.append("§7已迁移项目: ").append(String.join(", ", result.getMigratedItems())).append("\n\n");
                    sb.append("§a请重新连接服务器，并使用原密码直接登录！");

                    player.kick(Component.text(sb.toString()));
                } else {
                    player.sendMessage("§c[账号迁移失败] §f" + result.getMessage());
                }
            });
        });
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmdName = command.getName().toLowerCase(Locale.ROOT);

        if ("migrateaccount".equals(cmdName)) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§c此命令仅限在线玩家执行。");
                return true;
            }

            Player player = (Player) sender;
            handleMigrationExecution(player, label, args);
            return true;
        }

        if ("migrationadmin".equals(cmdName)) {
            if (!sender.hasPermission("accountmigration.admin")) {
                sender.sendMessage("§c您没有权限执行此管理员命令。");
                return true;
            }

            if (args.length == 0) {
                sender.sendMessage("§6=== AccountMigration 管理员指令 ===");
                sender.sendMessage("§e/migrationadmin unban <IP|all> §7- 手动解除指定 IP 或全部 IP 的防爆破锁定");
                sender.sendMessage("§e/migrationadmin lockouts §7- 查看当前被锁定的 IP 列表及剩余时间");
                sender.sendMessage("§e/migrationadmin reload §7- 重载插件配置并清空所有临时 IP 锁定");
                sender.sendMessage("§e/migrationadmin log [行数] §7- 查看最近的迁移审计日志");
                return true;
            }

            String sub = args[0].toLowerCase(Locale.ROOT);
            if ("reload".equals(sub)) {
                reloadConfig();
                migrationManager.clearAllLockouts();
                sender.sendMessage("§a[AccountMigration] 配置已重载，所有防爆破临时 IP 锁定已立即清空！");
                return true;
            }

            if ("unban".equals(sub) || "pardon".equals(sub) || "unlock".equals(sub)) {
                if (args.length < 2) {
                    sender.sendMessage("§c用法: /migrationadmin unban <目标IP | all>");
                    return true;
                }
                String targetIp = args[1].trim();
                if ("all".equalsIgnoreCase(targetIp)) {
                    migrationManager.clearAllLockouts();
                    sender.sendMessage("§a[AccountMigration] 已成功清空所有 IP 的防爆破锁定状态！");
                    return true;
                }
                boolean reset = migrationManager.resetLockout(targetIp);
                if (reset) {
                    sender.sendMessage("§a[AccountMigration] 已成功解除 IP §e" + targetIp + "§a 的锁定状态！");
                } else {
                    sender.sendMessage("§e[AccountMigration] IP §f" + targetIp + "§e 当前未处于锁定状态。");
                }
                return true;
            }

            if ("lockouts".equals(sub) || "listlocks".equals(sub) || "locks".equals(sub)) {
                Map<String, Long> active = migrationManager.getActiveLockouts();
                if (active.isEmpty()) {
                    sender.sendMessage("§a[AccountMigration] 当前没有被锁定的 IP。");
                } else {
                    sender.sendMessage("§6=== 当前被锁定的 IP (" + active.size() + " 个) ===");
                    active.forEach((ip, sec) -> sender.sendMessage("§eIP: §f" + ip + " §7(剩余 §c" + sec + " §7秒)"));
                }
                return true;
            }

            if ("log".equals(sub)) {
                int lines = 10;
                if (args.length > 1) {
                    try {
                        lines = Integer.parseInt(args[1]);
                    } catch (NumberFormatException ignored) {}
                }

                File logFile = new File(getDataFolder(), "migration_audit.log");
                if (!logFile.exists()) {
                    sender.sendMessage("§7暂无迁移审计日志。");
                    return true;
                }

                try {
                    List<String> allLines = Files.readAllLines(logFile.toPath());
                    int start = Math.max(0, allLines.size() - lines);
                    sender.sendMessage("§6=== 最近 " + (allLines.size() - start) + " 条迁移日志 ===");
                    for (int i = start; i < allLines.size(); i++) {
                        sender.sendMessage("§7" + allLines.get(i));
                    }
                } catch (Exception e) {
                    sender.sendMessage("§c读取日志失败: " + e.getMessage());
                }
                return true;
            }

            sender.sendMessage("§c未知管理员子命令。输入 /migrationadmin 查看帮助。");
            return true;
        }

        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String cmdName = command.getName().toLowerCase(Locale.ROOT);
        if ("migrateaccount".equals(cmdName)) {
            if (args.length == 1) {
                return List.of("<旧玩家名字>");
            }
            if (args.length == 2) {
                return List.of("<旧账号密码>");
            }
        }

        if ("migrationadmin".equals(cmdName) && sender.hasPermission("accountmigration.admin")) {
            if (args.length == 1) {
                List<String> list = new ArrayList<>();
                for (String opt : List.of("unban", "lockouts", "reload", "log")) {
                    if (opt.startsWith(args[0].toLowerCase())) list.add(opt);
                }
                return list;
            }
            if (args.length == 2 && ("unban".equalsIgnoreCase(args[0]) || "unlock".equalsIgnoreCase(args[0]))) {
                List<String> list = new ArrayList<>();
                list.add("all");
                list.addAll(migrationManager.getActiveLockouts().keySet());
                return list;
            }
        }
        return List.of();
    }
}
