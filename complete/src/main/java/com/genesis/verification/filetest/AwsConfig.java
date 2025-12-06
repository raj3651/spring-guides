package com.genesis.verification.filetest;


import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.time.Duration;

@Configuration
public class AwsConfig {

    private static final long MB = 1024L * 1024L;

    /*@Bean
    public S3AsyncClient s3AsyncClient() {
        return S3AsyncClient.builder()
                .region(Region.US_EAST_1)  // Your region
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .httpClient(NettyNioAsyncHttpClient.builder()
                        .maxConcurrency(100)
                        .build())
                //.httpClient(AwsCrtAsyncHttpClient.builder()
                        //.maxConcurrency(100)  // Parallelism limit
                        //.connectionTimeout(Duration.ofSeconds(30))  // Valid: Connect timeout
                        //.connectionAcquisitionTimeout(Duration.ofSeconds(60))  // Valid: Acquire connection
                        //.connectionMaxIdleTime(Duration.ofMinutes(5))  // Valid: Idle connection cleanup
                        //.build())
                .multipartEnabled(true)  // Enable parallel multipart
                .multipartConfiguration(MultipartConfiguration.builder()
                        .minimumPartSizeInBytes(8 * MB)  // 8 MB min chunk
                        .apiCallBufferSizeInBytes(64 * MB)  // 64 MB buffer (prevents stalls)
                        .build())
                .build();
    }

    @Bean
    public S3TransferManager s3TransferManager(S3AsyncClient s3AsyncClient) {
        return S3TransferManager.builder()
                .s3Client(s3AsyncClient)
                .build();
    }*/

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(Region.US_EAST_1)  // Your region
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .httpClient(ApacheHttpClient.builder()
                        .maxConnections(50)  // Pool for parallel ranges
                        .connectionTimeout(Duration.ofSeconds(30))
                        .socketTimeout(Duration.ofMinutes(10))  // Long for large chunks
                        .build())
                .serviceConfiguration(S3Configuration.builder()
                        .checksumValidationEnabled(true)  // Validates every byte
                        .build())
                .build();
    }
}
