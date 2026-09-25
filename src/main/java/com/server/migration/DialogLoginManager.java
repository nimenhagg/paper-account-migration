package com.server.migration;

import com.lenis0012.bukkit.loginsecurity.LoginSecurity;
import com.lenis0012.bukkit.loginsecurity.session.PlayerSession;
import com.lenis0012.bukkit.loginsecurity.session.SessionManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DialogLoginManager implements Listener {
    private final PaperAccountMigration plugin;
    private final Map<UUID, BukkitTask> promptTasks = new ConcurrentHashMap<>();

    public DialogLoginManager(PaperAccountMigration plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        startPromptTask(player);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        stopPromptTask(event.getPlayer().getUniqueId());
    }

    public void startPromptTask(Player player) {
        UUID uuid = player.getUniqueId();
        stopPromptTask(uuid);

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline()) {
                stopPromptTask(uuid);
                return;
            }

            if (isPlayerLoggedIn(player)) {
                stopPromptTask(uuid);
                return;
            }

            // Send interactive GUI-style prompt in chat
            sendInteractiveLoginPrompt(player);

        }, 20L, 200L); // First at 1s, repeat every 10s until logged in

        promptTasks.put(uuid, task);
    }

    public void stopPromptTask(UUID uuid) {
        BukkitTask task = promptTasks.remove(uuid);
        if (task != null) {
            task.cancel();
        }
    }

    private boolean isPlayerLoggedIn(Player player) {
        try {
            SessionManager sm = LoginSecurity.getSessionManager();
            if (sm != null) {
                PlayerSession session = sm.getPlayerSession(player);
                return session != null && session.isLoggedIn();
            }
        } catch (Exception e) {
            // Ignore if check fails
        }
        return false;
    }

    public void sendInteractiveLoginPrompt(Player player) {
        boolean registered = isPlayerRegistered(player);

        Component header = Component.text("═════════════ [ 服务器安全登录系统 ] ═════════════", NamedTextColor.GOLD, TextDecoration.BOLD);

        Component line1;
        Component actionBtn;

        if (registered) {
            line1 = Component.text("欢迎回来！您已注册账号，请点击下方按钮完成登录：", NamedTextColor.YELLOW);
            actionBtn = Component.text("【 点击此处快捷登录 】", NamedTextColor.GREEN, TextDecoration.BOLD)
                    .hoverEvent(HoverEvent.showText(Component.text("点击后在聊天框输入密码完成登录", NamedTextColor.GRAY)))
                    .clickEvent(ClickEvent.suggestCommand("/login "));
        } else {
            line1 = Component.text("欢迎加入！您是首次使用该名字，请选择以下操作：", NamedTextColor.AQUA);
            actionBtn = Component.text("【 点击设置新密码注册 】", NamedTextColor.GREEN, TextDecoration.BOLD)
                    .hoverEvent(HoverEvent.showText(Component.text("点击后在聊天框输入新密码进行注册", NamedTextColor.GRAY)))
                    .clickEvent(ClickEvent.suggestCommand("/register "));
        }

        Component migrateBtn = Component.text("【 旧账号改名数据迁移向导 】", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)
                .hoverEvent(HoverEvent.showText(Component.text("如果您换了新名字，点击此处可凭旧名字+旧密码一键继承背包与数据", NamedTextColor.YELLOW)))
                .clickEvent(ClickEvent.suggestCommand("/migrateaccount "));

        Component footer = Component.text("───────────────────────────────────────────────────", NamedTextColor.DARK_GRAY);

        player.sendMessage(Component.empty());
        player.sendMessage(header);
        player.sendMessage(line1);
        player.sendMessage(Component.text("  ➔ ", NamedTextColor.GRAY).append(actionBtn));
        if (!registered) {
            player.sendMessage(Component.text("  ➔ 若您是改名的老玩家：", NamedTextColor.GRAY).append(migrateBtn));
        }
        player.sendMessage(footer);
    }

    private boolean isPlayerRegistered(Player player) {
        try {
            SessionManager sm = LoginSecurity.getSessionManager();
            if (sm != null) {
                PlayerSession session = sm.getPlayerSession(player);
                return session != null && session.isRegistered();
            }
        } catch (Exception e) {
            // Ignore
        }
        return false;
    }
}
