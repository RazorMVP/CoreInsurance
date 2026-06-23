package com.nubeero.cia.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Read-replica connection settings ({@code cia.datasource.replica.*}, env
 * {@code CIA_DATASOURCE_REPLICA_*}). Only {@code url} is required to activate the
 * replica; username/password default to the primary's when blank (a read replica
 * usually shares the primary's credentials).
 *
 * @see ReadReplicaDataSourceConfig
 */
@ConfigurationProperties("cia.datasource.replica")
public class ReplicaDataSourceProperties {

    /** JDBC URL of the read replica, e.g. {@code jdbc:postgresql://replica-host:5432/cia}. */
    private String url;

    /** Optional — defaults to the primary's username when blank. */
    private String username;

    /** Optional — defaults to the primary's password when blank. */
    private String password;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
