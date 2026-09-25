package com.server.migration;

import java.util.ArrayList;
import java.util.List;

public class MigrationResult {
    private final boolean success;
    private final String message;
    private final List<String> migratedItems;

    public MigrationResult(boolean success, String message) {
        this.success = success;
        this.message = message;
        this.migratedItems = new ArrayList<>();
    }

    public MigrationResult(boolean success, String message, List<String> migratedItems) {
        this.success = success;
        this.message = message;
        this.migratedItems = migratedItems != null ? migratedItems : new ArrayList<>();
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public List<String> getMigratedItems() {
        return migratedItems;
    }
}
