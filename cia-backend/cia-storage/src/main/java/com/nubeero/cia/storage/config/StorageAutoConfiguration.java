package com.nubeero.cia.storage.config;

import io.minio.MinioClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageAutoConfiguration {

    @Bean
    @ConditionalOnProperty(name = "cia.storage.type", havingValue = "minio")
    public MinioClient minioClient(StorageProperties props) {
        return MinioClient.builder()
                .endpoint(props.getEndpoint())
                .credentials(props.getAccessKey(), props.getSecretKey())
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "cia.storage.type", havingValue = "s3")
    public S3Client s3Client(StorageProperties props) {
        return S3Client.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey())))
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "cia.storage.type", havingValue = "s3")
    public S3Presigner s3Presigner(StorageProperties props) {
        return S3Presigner.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(props.getAccessKey(), props.getSecretKey())))
                .endpointOverride(URI.create(props.getEndpoint()))
                .build();
    }
}
