package com.nubeero.cia.storage.impl;

import com.nubeero.cia.storage.DocumentStorageService;
import com.nubeero.cia.storage.config.StorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import java.io.InputStream;
import java.time.Duration;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cia.storage.type", havingValue = "s3")
public class S3StorageService implements DocumentStorageService {

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final StorageProperties storageProperties;

    @Override
    public String upload(String tenantId, String path, InputStream content, String mimeType) {
        String key = tenantId + "/" + path;
        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(storageProperties.getBucketName())
                            .key(key)
                            .contentType(mimeType)
                            .build(),
                    RequestBody.fromInputStream(content, -1)
            );
            log.info("Uploaded key={} bucket={}", key, storageProperties.getBucketName());
            return key;
        } catch (Exception e) {
            log.error("Failed to upload key={}", key, e);
            throw new RuntimeException("S3 upload failed: " + key, e);
        }
    }

    @Override
    public InputStream download(String tenantId, String path) {
        String key = tenantId + "/" + path;
        return s3Client.getObject(GetObjectRequest.builder()
                .bucket(storageProperties.getBucketName())
                .key(key)
                .build());
    }

    @Override
    public void delete(String tenantId, String path) {
        String key = tenantId + "/" + path;
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(storageProperties.getBucketName())
                .key(key)
                .build());
        log.info("Deleted key={}", key);
    }

    @Override
    public String presignedUrl(String tenantId, String path, long expirySeconds) {
        String key = tenantId + "/" + path;
        return s3Presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(Duration.ofSeconds(expirySeconds))
                .getObjectRequest(GetObjectRequest.builder()
                        .bucket(storageProperties.getBucketName())
                        .key(key)
                        .build())
                .build())
                .url()
                .toString();
    }
}
