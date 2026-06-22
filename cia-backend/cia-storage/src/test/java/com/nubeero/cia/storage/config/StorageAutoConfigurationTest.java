package com.nubeero.cia.storage.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.minio.MinioClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Verifies the storage provider abstraction wires the right client for
 * {@code cia.storage.type} — the swap-by-config contract the whole
 * {@code DocumentStorageService} design rests on. {@code minio} must create only
 * the MinIO client; {@code s3} only the S3 client + presigner; an unset type
 * neither (so a misconfig fails fast rather than silently picking a backend).
 * First test in {@code cia-storage} ({@code zero-test-modules} backlog).
 */
class StorageAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(UserConfigurations.of(StorageAutoConfiguration.class))
            .withPropertyValues(
                    "cia.storage.endpoint=http://localhost:9000",
                    "cia.storage.access-key=k",
                    "cia.storage.secret-key=s",
                    "cia.storage.region=us-east-1");

    @Test
    void minioType_createsOnlyMinioClient() {
        runner.withPropertyValues("cia.storage.type=minio").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(MinioClient.class);
            assertThat(ctx).doesNotHaveBean(S3Client.class);
            assertThat(ctx).doesNotHaveBean(S3Presigner.class);
        });
    }

    @Test
    void s3Type_createsS3ClientAndPresigner_notMinio() {
        runner.withPropertyValues("cia.storage.type=s3").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(S3Client.class);
            assertThat(ctx).hasSingleBean(S3Presigner.class);
            assertThat(ctx).doesNotHaveBean(MinioClient.class);
        });
    }

    @Test
    void noType_createsNeitherClient() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(MinioClient.class);
            assertThat(ctx).doesNotHaveBean(S3Client.class);
        });
    }
}
