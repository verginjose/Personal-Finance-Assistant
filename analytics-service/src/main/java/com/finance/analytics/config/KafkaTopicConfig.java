// com.finance.analytics.config.KafkaTopicConfig.java

package com.finance.analytics.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    public static final String CACHE_EVICT_TOPIC = "transaction-cache-evict";

    @Bean
    public NewTopic cacheEvictTopic() {
        return TopicBuilder.name(CACHE_EVICT_TOPIC)
                .partitions(3)       // 3 partitions — parallel consumption
                .replicas(1)         // 1 replica — increase in prod
                .build();
    }
}