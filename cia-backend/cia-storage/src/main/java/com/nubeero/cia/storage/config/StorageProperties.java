package com.nubeero.cia.storage.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "cia.storage")
public class StorageProperties {
    private String type;
    private String endpoint;
    private String bucketName;
    private String accessKey;
    private String secretKey;
    private String region;
}
