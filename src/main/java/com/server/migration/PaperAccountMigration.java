package com.server.migration;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PaperAccountMigration extends JavaPlugin implements CommandExecutor, TabCompleter {
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

        if (getCommand("migrateaccount") != null) {
            getCommand("migrateaccount").setExecutor(this);
            getCommand("migrateaccount").setTabCompleter(this);
        }

        if (getCommand("migrationadmin") != null) {
            getCommand("migrationadmin").setExecutor(this);
            getCommand("migrationadmin").setTabCompleter(this);
        }

        getLogger().info("PaperAccountMigration enabled successfully!");
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
            if (args.length < 2) {
                player.sendMessage("§6[账号迁移向导] §e用法: §f/" + label + " <旧游戏名字> <旧账号密码>");
                player.sendMessage("§7提示: 系统将自动验证旧密码并把背包物品、血量、经验、成就与登录数据一键平移至当前新名字！");
                return true;
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

            return true;
        }

        if ("migrationadmin".equals(cmdName)) {
            if (!sender.hasPermission("accountmigration.admin")) {
                sender.sendMessage("§c您没有权限执行此管理员命令。");
                return true;
            }

            if (args.length == 0) {
                sender.sendMessage("§6=== AccountMigration 管理员指令 ===");
                sender.sendMessage("§e/migrationadmin log [行数] §7- 查看最近的迁移审计日志");
                sender.sendMessage("§e/migrationadmin reload §7- 重载插件配置");
                return true;
            }

            String sub = args[0].toLowerCase(Locale.ROOT);
            if ("reload".equals(sub)) {
                reloadConfig();
                sender.sendMessage("§a[AccountMigration] 配置已重载。");
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

            sender.sendMessage("§c未知管理员子命令。");
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
                if ("log".startsWith(args[0].toLowerCase())) list.add("log");
                if ("reload".startsWith(args[0].toLowerCase())) list.add("reload");
                return list;
            }
        }
        return List.of();
    }
}
