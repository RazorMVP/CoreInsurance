package com.nubeero.cia.storage.impl;

import com.nubeero.cia.storage.DocumentStorageService;
import com.nubeero.cia.storage.config.StorageProperties;
import io.minio.*;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "cia.storage.type", havingValue = "minio")
public class MinioStorageService implements DocumentStorageService {

    private final MinioClient minioClient;
    private final StorageProperties storageProperties;

    @Override
    public String upload(String tenantId, String path, InputStream content, String mimeType) {
        String objectName = tenantId + "/" + path;
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(storageProperties.getBucketName())
                    .object(objectName)
                    .stream(content, -1, 10485760)
                    .contentType(mimeType)
                    .build());
            log.info("Uploaded object={} bucket={}", objectName, storageProperties.getBucketName());
            return objectName;
        } catch (Exception e) {
            log.error("Failed to upload object={}", objectName, e);
            throw new RuntimeException("Storage upload failed: " + objectName, e);
        }
    }

    @Override
    public InputStream download(String tenantId, String path) {
        String objectName = tenantId + "/" + path;
        try {
            return minioClient.getObject(GetObjectArgs.builder()
                    .bucket(storageProperties.getBucketName())
                    .object(objectName)
                    .build());
        } catch (Exception e) {
            log.error("Failed to download object={}", objectName, e);
            throw new RuntimeException("Storage download failed: " + objectName, e);
        }
    }

    @Override
    public void delete(String tenantId, String path) {
        String objectName = tenantId + "/" + path;
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(storageProperties.getBucketName())
                    .object(objectName)
                    .build());
            log.info("Deleted object={}", objectName);
        } catch (Exception e) {
            log.error("Failed to delete object={}", objectName, e);
            throw new RuntimeException("Storage delete failed: " + objectName, e);
        }
    }

    @Override
    public String presignedUrl(String tenantId, String path, long expirySeconds) {
        String objectName = tenantId + "/" + path;
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .bucket(storageProperties.getBucketName())
                    .object(objectName)
                    .method(Method.GET)
                    .expiry((int) expirySeconds, TimeUnit.SECONDS)
                    .build());
        } catch (Exception e) {
            log.error("Failed to generate presigned URL for object={}", objectName, e);
            throw new RuntimeException("Presigned URL generation failed: " + objectName, e);
        }
    }
}
