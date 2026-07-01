package com.ihrms.storage;

import com.ihrms.config.AppProperties;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

/**
 * S3-compatible object storage (AWS SDK for Java v2) configured from {@code S3_*} — point at local
 * MinIO, AWS, or Cloudflare R2 by changing endpoint + keys. Files are reached only via short-lived
 * presigned URLs; raw storage keys/URLs are never returned to clients (§6). Active by default; if
 * {@code S3_*} is unset the app still boots and uploads fail with 503 until configured.
 */
@Component
@ConditionalOnProperty(name = "app.storage.driver", havingValue = "s3")
public class S3StorageBackend implements StorageBackend {

  private static final Logger log = LoggerFactory.getLogger(S3StorageBackend.class);

  private final String bucket;
  private final S3Client client;
  private final S3Presigner presigner;
  private final boolean configured;

  public S3StorageBackend(AppProperties props) {
    AppProperties.S3 s3 = props.s3();
    this.bucket = s3 == null ? null : s3.bucket();
    String accessKeyId = s3 == null ? null : s3.accessKeyId();
    String secretAccessKey = s3 == null ? null : s3.secretAccessKey();

    if (isBlank(bucket) || isBlank(accessKeyId) || isBlank(secretAccessKey)) {
      this.client = null;
      this.presigner = null;
      this.configured = false;
      return;
    }

    Region region = Region.of(isBlank(s3.region()) ? "us-east-1" : s3.region());
    var credentials =
        StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKeyId, secretAccessKey));
    var serviceConfig =
        S3Configuration.builder().pathStyleAccessEnabled(s3.forcePathStyle()).build();
    URI endpoint = isBlank(s3.endpoint()) ? null : URI.create(s3.endpoint());

    var clientBuilder =
        S3Client.builder()
            .region(region)
            .credentialsProvider(credentials)
            .serviceConfiguration(serviceConfig);
    var presignerBuilder =
        S3Presigner.builder()
            .region(region)
            .credentialsProvider(credentials)
            .serviceConfiguration(serviceConfig);
    if (endpoint != null) {
      clientBuilder.endpointOverride(endpoint);
      presignerBuilder.endpointOverride(endpoint);
    }
    this.client = clientBuilder.build();
    this.presigner = presignerBuilder.build();
    this.configured = true;
  }

  @Override
  public boolean isConfigured() {
    return configured;
  }

  /** Ensure the bucket exists at startup; never fail boot if storage is down/unconfigured. */
  @PostConstruct
  void ensureBucketOnStartup() {
    if (!configured) {
      log.warn("S3 storage not configured (S3_* unset) — document uploads are disabled");
      return;
    }
    try {
      client.headBucket(b -> b.bucket(bucket));
      log.info("S3 bucket \"{}\" ready", bucket);
    } catch (S3Exception e) {
      if (e.statusCode() == 404) {
        createBucket();
      } else {
        log.error("Could not ensure bucket \"{}\": {}", bucket, e.getMessage());
      }
    } catch (RuntimeException e) {
      log.error("Could not reach S3 to ensure bucket \"{}\": {}", bucket, e.getMessage());
    }
  }

  private void createBucket() {
    try {
      client.createBucket(b -> b.bucket(bucket));
      log.info("S3 bucket \"{}\" created", bucket);
    } catch (RuntimeException e) {
      log.error("Could not create bucket \"{}\": {}", bucket, e.getMessage());
    }
  }

  @Override
  public String presignedPutUrl(String key, String contentType, int expiresInSeconds) {
    PutObjectRequest put =
        PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build();
    PutObjectPresignRequest presign =
        PutObjectPresignRequest.builder()
            .signatureDuration(Duration.ofSeconds(expiresInSeconds))
            .putObjectRequest(put)
            .build();
    return requirePresigner().presignPutObject(presign).url().toString();
  }

  @Override
  public String presignedGetUrl(String key, int expiresInSeconds) {
    GetObjectRequest get = GetObjectRequest.builder().bucket(bucket).key(key).build();
    GetObjectPresignRequest presign =
        GetObjectPresignRequest.builder()
            .signatureDuration(Duration.ofSeconds(expiresInSeconds))
            .getObjectRequest(get)
            .build();
    return requirePresigner().presignGetObject(presign).url().toString();
  }

  @Override
  public void putObject(String key, byte[] bytes, String contentType) {
    PutObjectRequest put =
        PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build();
    requireClient().putObject(put, software.amazon.awssdk.core.sync.RequestBody.fromBytes(bytes));
  }

  @Override
  public byte[] getObjectBytes(String key) {
    ResponseBytes<GetObjectResponse> bytes =
        requireClient().getObjectAsBytes(b -> b.bucket(bucket).key(key));
    return bytes.asByteArray();
  }

  @Override
  public void delete(String key) {
    requireClient().deleteObject(b -> b.bucket(bucket).key(key));
  }

  private S3Client requireClient() {
    if (client == null) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "File storage is not configured");
    }
    return client;
  }

  private S3Presigner requirePresigner() {
    if (presigner == null) {
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "File storage is not configured");
    }
    return presigner;
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
