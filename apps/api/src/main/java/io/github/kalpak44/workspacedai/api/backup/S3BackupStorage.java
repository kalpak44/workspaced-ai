package io.github.kalpak44.workspacedai.api.backup;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.time.Duration;
import java.util.OptionalLong;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Any S3-compatible store. Path-style, since a self-hosted one rarely has a DNS name per bucket.
 * Checksums only where S3 requires them: the SDK otherwise adds trailers to every request, which
 * not every compatible server accepts.
 */
class S3BackupStorage implements BackupStorage, AutoCloseable {

  private final S3Client client;
  private final S3Presigner presigner;
  private final String bucket;

  S3BackupStorage(BackupProperties properties) {
    var credentials =
        StaticCredentialsProvider.create(
            AwsBasicCredentials.create(properties.accessKey(), properties.secretKey()));
    var region = Region.of(properties.region());
    var configuration = S3Configuration.builder().pathStyleAccessEnabled(true).build();
    this.bucket = properties.bucket();
    this.client =
        S3Client.builder()
            .endpointOverride(properties.endpoint())
            .region(region)
            .credentialsProvider(credentials)
            .serviceConfiguration(configuration)
            .httpClient(UrlConnectionHttpClient.create())
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
            .build();
    this.presigner =
        S3Presigner.builder()
            .endpointOverride(properties.endpoint())
            .region(region)
            .credentialsProvider(credentials)
            .serviceConfiguration(configuration)
            .build();
  }

  @Override
  public URI uploadUrl(String key, Duration ttl) {
    return uri(
        presigner
            .presignPutObject(
                r -> r.signatureDuration(ttl).putObjectRequest(o -> o.bucket(bucket).key(key)))
            .url());
  }

  @Override
  public URI downloadUrl(String key, Duration ttl) {
    return uri(
        presigner
            .presignGetObject(
                r -> r.signatureDuration(ttl).getObjectRequest(o -> o.bucket(bucket).key(key)))
            .url());
  }

  @Override
  public OptionalLong size(String key) {
    try {
      return OptionalLong.of(client.headObject(r -> r.bucket(bucket).key(key)).contentLength());
    } catch (S3Exception e) {
      if (e.statusCode() == 404) {
        return OptionalLong.empty();
      }
      throw e;
    }
  }

  @Override
  public void delete(String key) {
    client.deleteObject(r -> r.bucket(bucket).key(key));
  }

  @Override
  public void close() {
    presigner.close();
    client.close();
  }

  private static URI uri(URL url) {
    try {
      return url.toURI();
    } catch (URISyntaxException e) {
      throw new IllegalStateException("the storage signed an invalid URL", e);
    }
  }
}
