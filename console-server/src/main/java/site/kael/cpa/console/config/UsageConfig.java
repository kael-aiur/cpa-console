package site.kael.cpa.console.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import site.kael.cpa.console.core.cpa.client.CpaApiClient;
import site.kael.cpa.console.core.usage.HttpUsageEventSource;
import site.kael.cpa.console.core.usage.RedisUsageEventSource;
import site.kael.cpa.console.core.usage.UsageEventSource;
import site.kael.cpa.console.core.usage.redis.RedisUsageClient;

import javax.sql.DataSource;
import java.time.Duration;

@Configuration
public class UsageConfig {
    @Bean
    public DataSourceTransactionManager transactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    public RedisUsageClient redisUsageClient(
            @Value("${cpa.base-url:${CPA_BASE_URL:http://127.0.0.1:8317}}") String baseUrl,
            @Value("${cpa.redis-addr:${CPA_REDIS_ADDR:}}") String redisAddress,
            @Value("${cpa.management-key:${CPA_MANAGEMENT_KEY:}}") String managementKey,
            @Value("${cpa.timeout-ms:${CPA_TIMEOUT_MS:5000}}") long timeoutMs,
            @Value("${cpa.redis-tls:${CPA_REDIS_TLS:false}}") boolean tls,
            @Value("${cpa.usage-batch-size:${CPA_USAGE_BATCH_SIZE:1000}}") int batchSize
    ) {
        return new RedisUsageClient(baseUrl, redisAddress, managementKey, Duration.ofMillis(timeoutMs), tls, batchSize);
    }

    @Bean
    public UsageEventSource usageEventSource(
            @Value("${cpa.usage-mode:${CPA_USAGE_MODE:http}}") String mode,
            RedisUsageClient redisUsageClient,
            CpaApiClient cpaApiClient,
            @Value("${cpa.timeout-ms:${CPA_TIMEOUT_MS:5000}}") long timeoutMs,
            @Value("${cpa.usage-fallback-interval:${CPA_USAGE_FALLBACK_INTERVAL:1m}}") Duration fallbackInterval,
            @Value("${cpa.usage-batch-size:${CPA_USAGE_BATCH_SIZE:1000}}") int batchSize
    ) {
        if ("http".equalsIgnoreCase(mode.trim())) {
            return new HttpUsageEventSource(cpaApiClient, Duration.ofMillis(timeoutMs), fallbackInterval, batchSize);
        }
        if (!"redis".equalsIgnoreCase(mode.trim())) {
            throw new IllegalArgumentException("cpa.usage-mode must be redis or http");
        }
        return new RedisUsageEventSource(redisUsageClient, fallbackInterval, batchSize);
    }
}
