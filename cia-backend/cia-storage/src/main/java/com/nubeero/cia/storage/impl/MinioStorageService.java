package com.nubeero.cia.storage.impl;

import com.nubeero.cia.storage.DocumentStorageService;
import com.nubeero.cia.storage.config.StorageProperties;
import io.minio.*;
import io.minio.http.Method;
import jakarta.annotation.PostConstruct;
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

    /**
     * Ensures the configured bucket exists on startup. Without this, every
     * first-time upload (policy PDFs, claim DVs, NAICOM artifacts, KYC docs)
     * fails with {@code NoSuchBucket} until an operator creates it
     * out-of-band — surfaced during F5.16 NAICOM artifact smoke testing
     * where the dev MinIO ships empty.
     *
     * <p>Failures here are intentionally non-fatal: object-storage may be
     * temporarily unreachable at boot or the configured credentials may
     * lack {@code s3:CreateBucket}; the application should still start and
     * surface upload errors on the request path rather than crash-looping.
     * Testcontainers-based ITs are unaffected because the
     * {@code MinIOContainer} module auto-creates a bucket per container.
     */
    @PostConstruct
    void ensureBucketExists() {
        String bucket = storageProperties.getBucketName();
        if (bucket == null || bucket.isBlank()) {
            log.warn("cia.storage.bucket-name is unset; skipping bucket bootstrap.");
            return;
        }
        try {
            boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                    .bucket(bucket)
                    .build());
            if (exists) {
                log.info("MinIO bucket={} already exists.", bucket);
                return;
            }
            minioClient.makeBucket(MakeBucketArgs.builder()
                    .bucket(bucket)
                    .build());
            log.info("MinIO bucket={} created on startup.", bucket);
        } catch (Exception e) {
            // Non-fatal — the application still boots; uploads will surface
            // the real error per-request if the bucket genuinely cannot be
            // reached or created.
            log.warn("MinIO bucket bootstrap failed for bucket={} — uploads will fail until resolved: {}",
                bucket, e.getMessage());
        }
    }

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
