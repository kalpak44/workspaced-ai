package io.github.kalpak44.workspacedai.api.backup;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param endpoint the S3 API's base URL; unset turns backups off, and every backup route answers
 *     503
 * @param bucket where every user's archives go, each under the user's own prefix
 * @param region S3 signs with one even where the server ignores it
 * @param uploadTtl how long a workspace has to archive and upload its home
 * @param restoreTtl how long a restore's download link works; S3 allows at most seven days
 * @param pollInterval how often a running backup's progress is read from its workspace
 * @param stallTimeout how long a running backup may go without progress before it is failed
 */
@ConfigurationProperties("workspaced.backup")
public record BackupProperties(
    URI endpoint,
    @DefaultValue("workspace-backups") String bucket,
    @DefaultValue("us-east-1") String region,
    String accessKey,
    String secretKey,
    @DefaultValue("6h") Duration uploadTtl,
    @DefaultValue("7d") Duration restoreTtl,
    @DefaultValue("1s") Duration pollInterval,
    @DefaultValue("10m") Duration stallTimeout) {}
