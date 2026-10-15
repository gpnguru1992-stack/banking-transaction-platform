package com.bank.customer.config;

import com.bank.customer.api.CustomerDtos.CustomerResponse;
import com.bank.customer.service.CustomerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;

@Configuration
public class CacheConfig {

    /** Customer profiles are read on every transfer; cache them in Redis with a short TTL. */
    @Bean
    RedisCacheManagerBuilderCustomizer customerCache(ObjectMapper objectMapper,
                                                    @Value("${banking.cache.customer-ttl:PT10M}") Duration ttl) {
        Jackson2JsonRedisSerializer<CustomerResponse> serializer =
                new Jackson2JsonRedisSerializer<>(objectMapper, CustomerResponse.class);
        return builder -> builder.withCacheConfiguration(CustomerService.CACHE,
                RedisCacheConfiguration.defaultCacheConfig()
                        .entryTtl(ttl)
                        .disableCachingNullValues()
                        .prefixCacheNameWith("customer-service::")
                        .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer)));
    }
}
