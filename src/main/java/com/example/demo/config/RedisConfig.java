package com.example.demo.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 相关配置
 *
 * <p>关键点：
 * <ul>
 *   <li>RedisTemplate 默认 JDK 序列化，可读性差且跨语言不兼容 → 改成 String(key) + JSON(value)</li>
 *   <li>加 {@link EnableCaching} 开启 Spring Cache 抽象，让 {@code @Cacheable} 生效</li>
 *   <li>CacheManager 交给 Spring Boot 根据 {@code spring.cache.type=redis} 自动配置，
 *       TTL / key-prefix 走 application.yaml 里的 {@code spring.cache.redis.*}</li>
 * </ul>
 */
@Configuration
@EnableCaching
public class RedisConfig {

    /**
     * 通用 RedisTemplate&lt;String, Object&gt;：
     * key/hashKey 用 String，value/hashValue 用 JSON。
     * 直接注入使用即可，不需要业务代码再手动指定序列化器。
     */
    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        // key 一律用 UTF-8 字符串，方便 memurai-cli 里 KEYS/GET 观察
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // value 用 Jackson JSON 序列化，可读性好
        GenericJackson2JsonRedisSerializer jsonSerializer = buildJsonSerializer();
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * 构建带类型信息的 JSON 序列化器：
     * - 打开默认类型标注，反序列化时能还原为原始类（如 List&lt;School&gt;）
     * - 用 BasicPolymorphicTypeValidator 限制反序列化白名单，避免通用 Object 反序列化的安全隐患
     */
    private GenericJackson2JsonRedisSerializer buildJsonSerializer() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        // 只允许项目自身包 + JDK 常用容器类被反序列化，缩小攻击面
        BasicPolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.example.demo.")
                .allowIfSubType("java.util.")
                .allowIfSubType("java.lang.")
                .build();
        mapper.activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
        return new GenericJackson2JsonRedisSerializer(mapper);
    }
}
