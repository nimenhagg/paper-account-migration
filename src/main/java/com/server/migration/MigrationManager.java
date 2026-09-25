package com.server.migration;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.lenis0012.bukkit.loginsecurity.hashing.Algorithm;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class MigrationManager {
    private final PaperAccountMigration plugin;
    private final Logger logger;
    private final MigrationAuditLogger auditLogger;
    private final File serverDir;
    private final Map<String, Integer> failedAttempts = new ConcurrentHashMap<>();
    private final Map<String, Long> lockoutExpiry = new ConcurrentHashMap<>();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public MigrationManager(PaperAccountMigration plugin, MigrationAuditLogger auditLogger) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.auditLogger = auditLogger;
        this.serverDir = plugin.getServer().getWorldContainer();
    }

    public MigrationResult performMigration(Player player, String oldUsername, String oldPassword) {
        String newUsername = player.getName();
        String newUuid = player.getUniqueId().toString();
        String ip = player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "unknown";

        // Check lockout
        long now = System.currentTimeMillis();
        if (lockoutExpiry.containsKey(ip) && lockoutExpiry.get(ip) > now) {
            long remainingSec = (lockoutExpiry.get(ip) - now) / 1000;
            return new MigrationResult(false, "由于连续密码错误，该 IP 已被暂时锁定，请等待 " + remainingSec + " 秒后重试。");
        }

        // Validate basic rules
        if (newUsername.equalsIgnoreCase(oldUsername)) {
            return new MigrationResult(false, "目标旧账号名称与当前名称相同，无需迁移。");
        }

        Player onlineOldPlayer = Bukkit.getPlayerExact(oldUsername);
        if (onlineOldPlayer != null && onlineOldPlayer.isOnline()) {
            auditLogger.log(ip, newUsername, newUuid, oldUsername, "online", false, "Old player is currently online");
            return new MigrationResult(false, "无法迁移正在在线的玩家账号！");
        }

        // Step 1: Look up old account from LoginSecurity SQLite
        File loginDbFile = new File(serverDir, "plugins/LoginSecurity/LoginSecurity.db");
        if (!loginDbFile.exists()) {
            return new MigrationResult(false, "未找到 LoginSecurity 数据库，无法验证旧账号。");
        }

        OldAccountRecord oldAccount = findOldAccount(loginDbFile, oldUsername);
        if (oldAccount == null) {
            handleFailedAttempt(ip);
            auditLogger.log(ip, newUsername, newUuid, oldUsername, "none", false, "Old account not found");
            return new MigrationResult(false, "未找到名为 '" + oldUsername + "' 的旧账号，请检查拼写。");
        }

        // Step 2: Validate password hash
        Algorithm algo = Algorithm.getById(oldAccount.getHashingAlgorithm());
        if (algo == null) {
            return new MigrationResult(false, "旧账号哈希算法不受支持 (ID: " + oldAccount.getHashingAlgorithm() + ")");
        }

        boolean passMatch = false;
        try {
            passMatch = algo.check(oldPassword, oldAccount.getPassword());
        } catch (Exception e) {
            logger.warning("[Migration] Password verify error: " + e.getMessage());
        }

        if (!passMatch) {
            handleFailedAttempt(ip);
            auditLogger.log(ip, newUsername, newUuid, oldUsername, oldAccount.getUniqueUserId(), false, "Invalid password attempt");
            return new MigrationResult(false, "旧账号密码错误，请重新确认！");
        }

        // Password verified! Clear failed attempts
        failedAttempts.remove(ip);
        lockoutExpiry.remove(ip);

        String oldUuid = oldAccount.getUniqueUserId();
        List<String> migratedItems = new ArrayList<>();

        // Step 3: Create backup directory
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        File backupDir = new File(serverDir, "backups/migrations/" + timestamp + "_" + oldUsername + "_to_" + newUsername);
        backupDir.mkdirs();

        try {
            // Step 4: Migrate Vanilla player data
            migrateVanillaData(oldUuid, newUuid, backupDir, migratedItems);

            // Step 5: Migrate Multiverse-Inventories
            migrateMultiverseInventories(oldUuid, newUuid, newUsername, backupDir, migratedItems);

            // Step 6: Migrate Slimefun 5
            migrateSlimefunData(oldUuid, newUuid, backupDir, migratedItems);

            // Step 7: Migrate CoreProtect
            migrateCoreProtect(oldUuid, newUuid, oldUsername, newUsername, migratedItems);

            // Step 8: Update LoginSecurity database
            migrateLoginSecurityDb(loginDbFile, oldAccount.getId(), newUuid, newUsername, backupDir, migratedItems);

            // Log success
            auditLogger.log(ip, newUsername, newUuid, oldUsername, oldUuid, true,
                    "Successfully migrated items: " + String.join(", ", migratedItems));

            return new MigrationResult(true, "账号迁移成功！已完整继承旧账号数据。", migratedItems);

        } catch (Exception e) {
            logger.severe("[Migration] Fatal error during migration: " + e.getMessage());
            e.printStackTrace();
            auditLogger.log(ip, newUsername, newUuid, oldUsername, oldUuid, false, "Fatal exception: " + e.getMessage());
            return new MigrationResult(false, "迁移过程中出现异常: " + e.getMessage() + "，请联系管理员查看备份。");
        }
    }

    private void handleFailedAttempt(String ip) {
        int count = failedAttempts.getOrDefault(ip, 0) + 1;
        failedAttempts.put(ip, count);
        if (count >= 5) {
            lockoutExpiry.put(ip, System.currentTimeMillis() + (10 * 60 * 1000L)); // 10 minutes lockout
            logger.warning("[Migration] IP " + ip + " reached maximum failed attempts. Locked for 10 minutes.");
        }
    }

    private OldAccountRecord findOldAccount(File dbFile, String username) {
        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement ps = conn.prepareStatement("SELECT id, unique_user_id, last_name, password, hashing_algorithm FROM ls_players WHERE LOWER(last_name) = LOWER(?);")) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new OldAccountRecord(
                            rs.getInt("id"),
                            rs.getString("unique_user_id"),
                            rs.getString("last_name"),
                            rs.getString("password"),
                            rs.getInt("hashing_algorithm")
                    );
                }
            }
        } catch (Exception e) {
            logger.severe("[Migration] Error querying LoginSecurity db: " + e.getMessage());
        }
        return null;
    }

    private void migrateVanillaData(String oldUuid, String newUuid, File backupDir, List<String> migratedItems) {
        // Player data (.dat) in world/players/data/ or world/playerdata/
        File[] candidateDirs = new File[] {
                new File(serverDir, "world/players/data"),
                new File(serverDir, "world/playerdata")
        };

        for (File dir : candidateDirs) {
            if (dir.exists()) {
                File oldDat = new File(dir, oldUuid + ".dat");
                if (oldDat.exists()) {
                    File newDat = new File(dir, newUuid + ".dat");
                    copyFile(oldDat, newDat, backupDir);
                    migratedItems.add("Vanilla PlayerData (" + dir.getName() + ")");
                }
            }
        }

        // Stats in world/players/stats/ or world/stats/
        File[] statsDirs = new File[] {
                new File(serverDir, "world/players/stats"),
                new File(serverDir, "world/stats")
        };
        for (File dir : statsDirs) {
            if (dir.exists()) {
                File oldStats = new File(dir, oldUuid + ".json");
                if (oldStats.exists()) {
                    File newStats = new File(dir, newUuid + ".json");
                    copyFile(oldStats, newStats, backupDir);
                    migratedItems.add("Player Stats");
                }
            }
        }

        // Advancements in world/players/advancements/ or world/advancements/
        File[] advDirs = new File[] {
                new File(serverDir, "world/players/advancements"),
                new File(serverDir, "world/advancements")
        };
        for (File dir : advDirs) {
            if (dir.exists()) {
                File oldAdv = new File(dir, oldUuid + ".json");
                if (oldAdv.exists()) {
                    File newAdv = new File(dir, newUuid + ".json");
                    copyFile(oldAdv, newAdv, backupDir);
                    migratedItems.add("Player Advancements");
                }
            }
        }
    }

    private void migrateMultiverseInventories(String oldUuid, String newUuid, String newName, File backupDir, List<String> migratedItems) {
        File mviDir = new File(serverDir, "plugins/Multiverse-Inventories");
        if (!mviDir.exists()) return;

        // 1. Players directory
        File playersDir = new File(mviDir, "players");
        if (playersDir.exists()) {
            File oldFile = new File(playersDir, oldUuid + ".json");
            if (oldFile.exists()) {
                File newFile = new File(playersDir, newUuid + ".json");
                copyFile(oldFile, newFile, backupDir);
                migratedItems.add("Multiverse Players Data");
            }
        }

        // 2. Groups and worlds recursive
        for (String sub : new String[] { "groups", "worlds" }) {
            File subDir = new File(mviDir, sub);
            if (subDir.exists()) {
                searchAndCopyMvi(subDir, oldUuid, newUuid, backupDir, migratedItems);
            }
        }

        // 3. playernames.json
        File pnFile = new File(mviDir, "playernames.json");
        if (pnFile.exists()) {
            try {
                copyFile(pnFile, new File(backupDir, "playernames.json.bak"), null);
                Type type = new TypeToken<Map<String, String>>() {}.getType();
                Map<String, String> namesMap;
                try (FileReader reader = new FileReader(pnFile, StandardCharsets.UTF_8)) {
                    namesMap = gson.fromJson(reader, type);
                }
                if (namesMap != null) {
                    namesMap.remove(oldUuid);
                    namesMap.put(newUuid, newName);
                    try (FileWriter writer = new FileWriter(pnFile, StandardCharsets.UTF_8)) {
                        gson.toJson(namesMap, writer);
                    }
                    migratedItems.add("Multiverse PlayerNames Map");
                }
            } catch (Exception e) {
                logger.warning("[Migration] Failed to update playernames.json: " + e.getMessage());
            }
        }
    }

    private void searchAndCopyMvi(File dir, String oldUuid, String newUuid, File backupDir, List<String> migratedItems) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                searchAndCopyMvi(f, oldUuid, newUuid, backupDir, migratedItems);
            } else if (f.getName().equalsIgnoreCase(oldUuid + ".json")) {
                File newFile = new File(f.getParentFile(), newUuid + ".json");
                copyFile(f, newFile, backupDir);
                migratedItems.add("Multiverse " + f.getParentFile().getName() + " inventory");
            }
        }
    }

    private void migrateSlimefunData(String oldUuid, String newUuid, File backupDir, List<String> migratedItems) {
        File sfDir = new File(serverDir, "data-storage/Slimefun");
        if (!sfDir.exists()) return;

        // Players/<uuid>.yml
        File playersDir = new File(sfDir, "Players");
        if (playersDir.exists()) {
            File oldFile = new File(playersDir, oldUuid + ".yml");
            if (oldFile.exists()) {
                File newFile = new File(playersDir, newUuid + ".yml");
                copyFile(oldFile, newFile, backupDir);
                migratedItems.add("Slimefun Player Profile");
            }
        }

        // waypoints/<uuid>.yml
        File wpDir = new File(sfDir, "waypoints");
        if (wpDir.exists()) {
            File oldFile = new File(wpDir, oldUuid + ".yml");
            if (oldFile.exists()) {
                File newFile = new File(wpDir, newUuid + ".yml");
                copyFile(oldFile, newFile, backupDir);
                migratedItems.add("Slimefun Waypoints");
            }
        }
    }

    private void migrateCoreProtect(String oldUuid, String newUuid, String oldName, String newName, List<String> migratedItems) {
        File cpDb = new File(serverDir, "plugins/CoreProtect/database.db");
        if (!cpDb.exists()) return;

        String url = "jdbc:sqlite:" + cpDb.getAbsolutePath();
        try (Connection conn = DriverManager.getConnection(url)) {
            // Update co_user table
            try (PreparedStatement ps = conn.prepareStatement("UPDATE co_user SET uuid = ? WHERE uuid = ?;")) {
                ps.setString(1, newUuid);
                ps.setString(2, oldUuid);
                int rows = ps.executeUpdate();
                if (rows > 0) {
                    migratedItems.add("CoreProtect History (" + rows + " user records)");
                }
            }
        } catch (Exception e) {
            logger.warning("[Migration] CoreProtect DB update notice: " + e.getMessage());
        }
    }

    private void migrateLoginSecurityDb(File dbFile, int oldAccountId, String newUuid, String newName, File backupDir, List<String> migratedItems) throws Exception {
        copyFile(dbFile, new File(backupDir, "LoginSecurity.db.bak"), null);

        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        try (Connection conn = DriverManager.getConnection(url)) {
            // Remove any new placeholder record if player connected before migration
            try (PreparedStatement psDel = conn.prepareStatement("DELETE FROM ls_players WHERE (unique_user_id = ? OR LOWER(last_name) = LOWER(?)) AND id != ?;")) {
                psDel.setString(1, newUuid);
                psDel.setString(2, newName);
                psDel.setInt(3, oldAccountId);
                psDel.executeUpdate();
            }

            // Update old account record to new UUID and new name
            try (PreparedStatement psUpd = conn.prepareStatement("UPDATE ls_players SET unique_user_id = ?, last_name = ? WHERE id = ?;")) {
                psUpd.setString(1, newUuid);
                psUpd.setString(2, newName);
                psUpd.setInt(3, oldAccountId);
                int rows = psUpd.executeUpdate();
                if (rows > 0) {
                    migratedItems.add("LoginSecurity Account Credentials");
                }
            }
        }
    }

    private void copyFile(File src, File dest, File backupDir) {
        try {
            if (backupDir != null && dest.exists()) {
                File bkp = new File(backupDir, dest.getName() + ".orig");
                Files.copy(dest.toPath(), bkp.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.copy(src.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            logger.warning("[Migration] Copy failed: " + src.getName() + " -> " + dest.getName() + ": " + e.getMessage());
        }
    }
}
