package com.server.migration;

public class OldAccountRecord {
    private final int id;
    private final String uniqueUserId;
    private final String lastName;
    private final String password;
    private final int hashingAlgorithm;

    public OldAccountRecord(int id, String uniqueUserId, String lastName, String password, int hashingAlgorithm) {
        this.id = id;
        this.uniqueUserId = uniqueUserId;
        this.lastName = lastName;
        this.password = password;
        this.hashingAlgorithm = hashingAlgorithm;
    }

    public int getId() {
        return id;
    }

    public String getUniqueUserId() {
        return uniqueUserId;
    }

    public String getLastName() {
        return lastName;
    }

    public String getPassword() {
        return password;
    }

    public int getHashingAlgorithm() {
        return hashingAlgorithm;
    }
}
