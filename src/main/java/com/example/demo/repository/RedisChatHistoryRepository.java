package com.example.demo.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 会话历史仓库的 Redis 实现，服务重启不丢失。
 *
 * <p>数据结构选型：
 * <ul>
 *   <li>用 Redis Set，key 为 {@code chat_history:{type}}，value 是若干 chatId</li>
 *   <li>Set 天然去重，正好对应内存版里的 {@code chatIds.contains(chatId)} 判断</li>
 *   <li>SADD / SMEMBERS 都是 O(1)/O(N)，并发安全</li>
 * </ul>
 *
 * <p>为什么用 {@link StringRedisTemplate} 而不是通用 RedisTemplate：
 * chatId 是纯字符串，不需要 JSON 序列化，直接用 String 类型的 Template 更轻量。
 *
 * <p>{@link Primary} 表示当同时存在 {@link InMemoryChatHistoryRepository} 时，
 * Spring 默认注入这个 Redis 实现。内存版保留在代码里作为学习对照。
 */
@Primary
@Component
@RequiredArgsConstructor
public class RedisChatHistoryRepository implements ChatHistoryRepository {

    // 所有 chatId 集合的 key 前缀，例如 chat_history:chat、chat_history:service
    // 注意：Spring Cache 的 key-prefix 只影响 @Cacheable 生成的 key，这里是业务数据不走 Cache，前缀独立
    private static final String KEY_PREFIX = "chat_history:";

    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void save(String type, String chatId) {
        // SADD chat_history:{type} {chatId}
        // Set 自动去重：即使多次调用 save 同一个 chatId，也只会有一份
        stringRedisTemplate.opsForSet().add(KEY_PREFIX + type, chatId);
    }

    @Override
    public List<String> getChatIds(String type) {
        // SMEMBERS chat_history:{type}
        Set<String> members = stringRedisTemplate.opsForSet().members(KEY_PREFIX + type);
        // members 可能为 null（key 不存在），也可能为空集合
        // 返回不可变副本，防止调用方修改内部状态
        return (members == null || members.isEmpty()) ? List.of() : List.copyOf(members);
    }
}
