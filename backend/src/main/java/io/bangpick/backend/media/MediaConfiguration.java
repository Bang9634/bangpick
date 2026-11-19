package io.bangpick.backend.media;

import com.oracle.bmc.ClientConfiguration;
import com.oracle.bmc.auth.ConfigFileAuthenticationDetailsProvider;
import com.oracle.bmc.objectstorage.ObjectStorageClient;
import com.oracle.bmc.retrier.RetryConfiguration;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "bangpick.media.enabled", havingValue = "true")
@EnableConfigurationProperties(MediaConfiguration.Settings.class)
class MediaConfiguration {
    @ConfigurationProperties("bangpick.media")
    record Settings(String devToken, String workerToken, String namespace, String bucket,
                    String region, String configFile, String profile) {
        @Override public String toString() { return "MediaSettings[redacted]"; }
    }

    @Bean
    MediaAccess mediaAccess(Settings settings, @Value("${bangpick.auth.enabled:false}") boolean memberLoginEnabled) {
        return new MediaAccess(memberLoginEnabled, settings.devToken(), settings.workerToken());
    }

    @Bean
    MediaJobRepository mediaJobRepository(JdbcTemplate jdbc) {
        return new MediaJobRepository(jdbc);
    }

    @Bean
    MediaJobs mediaJobs(MediaJobRepository repository, MediaStorage storage) {
        return new MediaJobs(repository, storage);
    }

    @Bean(destroyMethod = "close")
    ObjectStorageClient mediaOciClient(Settings settings) throws IOException {
        if (!StringUtils.hasText(settings.configFile()) || !StringUtils.hasText(settings.region())
                || !StringUtils.hasText(settings.namespace()) || !StringUtils.hasText(settings.bucket())) {
            throw new IllegalStateException("Set OCI_CONFIG_FILE, OCI_REGION, OCI_NAMESPACE and OCI_BUCKET");
        }
        var auth = new ConfigFileAuthenticationDetailsProvider(settings.configFile(), settings.profile());
        return ObjectStorageClient.builder()
                .region(settings.region())
                .configuration(ClientConfiguration.builder()
                        .connectionTimeoutMillis(5_000).readTimeoutMillis(10_000)
                        // 링크 발급에 성공한 뒤 응답만 유실되면, 자동 재시도로 중복 발급될 수 있다.
                        .retryConfiguration(RetryConfiguration.NO_RETRY_CONFIGURATION).build())
                .build(auth);
    }

    @Bean
    MediaStorage mediaStorage(ObjectStorageClient client, Settings settings) {
        return new OciMediaStorage(client, settings.namespace(), settings.bucket());
    }
}
