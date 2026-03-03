package com.nearbyfreinds.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import com.nearbyfreinds.hash.ConsistentHashRing;

@Configuration
public class RedisConfig {

    @Value("${app.redis-cache-host}")
    private String cacheHost;

    @Value("${app.redis-cache-port}")
    private int cachePort;

    @Value("${app.redis-pubsub-nodes}")
    private String pubsubNodes;

    @Bean
    public ConsistentHashRing<String> consistentHashRing() {
        ConsistentHashRing<String> ring = new ConsistentHashRing<>();
        for (String node : pubsubNodes.split(",")) {
            ring.addNode(node.trim());
        }
        return ring;
    }

    @Bean
    @Primary
    public LettuceConnectionFactory cacheRedisConnectionFactory() {
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(cacheHost, cachePort);
        return new LettuceConnectionFactory(config);
    }

    @Bean("cacheRedisTemplate")
    @Primary
    public StringRedisTemplate cacheRedisTemplate(LettuceConnectionFactory cacheRedisConnectionFactory) {
        return new StringRedisTemplate(cacheRedisConnectionFactory);
    }

    @Bean
    public Map<String, LettuceConnectionFactory> pubsubConnectionFactories() {
        Map<String, LettuceConnectionFactory> factories = new LinkedHashMap<>();
        for (String node : pubsubNodes.split(",")) {
            String trimmed = node.trim();
            String[] parts = trimmed.split(":");
            RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
                    parts[0], Integer.parseInt(parts[1]));
            LettuceConnectionFactory factory = new LettuceConnectionFactory(config);
            factory.afterPropertiesSet();
            factories.put(trimmed, factory);
        }
        return factories;
    }

    @Bean
    public Map<String, StringRedisTemplate> pubsubTemplates(
            Map<String, LettuceConnectionFactory> pubsubConnectionFactories) {
        Map<String, StringRedisTemplate> templates = new LinkedHashMap<>();
        for (Map.Entry<String, LettuceConnectionFactory> entry : pubsubConnectionFactories.entrySet()) {
            templates.put(entry.getKey(), new StringRedisTemplate(entry.getValue()));
        }
        return templates;
    }

    @Bean
    public Map<String, RedisMessageListenerContainer> pubsubContainers(
            Map<String, LettuceConnectionFactory> pubsubConnectionFactories,
            TaskExecutor pubsubTaskExecutor) {
        Map<String, RedisMessageListenerContainer> containers = new LinkedHashMap<>();
        for (Map.Entry<String, LettuceConnectionFactory> entry : pubsubConnectionFactories.entrySet()) {
            RedisMessageListenerContainer container = new RedisMessageListenerContainer();
            container.setConnectionFactory(entry.getValue());
            container.setTaskExecutor(pubsubTaskExecutor);
            container.afterPropertiesSet();
            container.start();
            containers.put(entry.getKey(), container);
        }
        return containers;
    }

    @Bean
    public TaskExecutor pubsubTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("redis-pubsub-");
        return executor;
    }
}
