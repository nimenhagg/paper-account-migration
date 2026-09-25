package com.server.migration;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

public class MigrationAuditLogger {
    private final File logFile;
    private final Logger logger;
    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public MigrationAuditLogger(File dataFolder, Logger logger) {
        this.logFile = new File(dataFolder, "migration_audit.log");
        this.logger = logger;
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
    }

    public synchronized void log(String ip, String newName, String newUuid, String oldName, String oldUuid, boolean success, String details) {
        String time = LocalDateTime.now().format(formatter);
        String entry = String.format("[%s] [IP: %s] [Result: %s] NewPlayer: %s (%s) <- OldPlayer: %s (%s) | Details: %s",
                time, ip, success ? "SUCCESS" : "FAILED", newName, newUuid, oldName, oldUuid, details);

        if (success) {
            logger.info("[MigrationAudit] " + entry);
        } else {
            logger.warning("[MigrationAudit] " + entry);
        }

        try (FileWriter fw = new FileWriter(logFile, StandardCharsets.UTF_8, true);
             PrintWriter pw = new PrintWriter(fw)) {
            pw.println(entry);
        } catch (Exception e) {
            logger.severe("[MigrationAudit] Failed to write audit log: " + e.getMessage());
        }
    }
}
