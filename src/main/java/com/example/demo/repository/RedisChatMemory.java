package com.example.demo.repository;

import com.example.demo.exception.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 会话消息内容的 Redis 实现，取代 InMemoryChatMemory，让 AI 重启后仍记得历史对话。
 *
 * <p>数据结构：Redis List
 * <ul>
 *   <li>key = {@code chat_memory:{conversationId}}</li>
 *   <li>value = List&lt;JSON&gt;，每条 JSON 是一个 {@link PersistedMessage}</li>
 *   <li>RPUSH 追加、LRANGE 读取、DEL 清空 —— 天然支持"最近 N 条"这种 lastN 语义</li>
 * </ul>
 *
 * <p>为什么用中间 DTO 而不是直接序列化 Message：
 * Spring AI 的 Message 子类（UserMessage/AssistantMessage/...）内含 Media（图片/PDF 资源）
 * 和 ToolCall（工具调用参数），无法直接 JSON 化。对"会话记忆"这个用途来说，只有 text 和
 * MessageType 是必要的 —— Media 是当次交互的临时资源，历史里保存无意义。
 *
 * <p>取舍：
 * <ul>
 *   <li>不持久化 Media（图片、PDF 等 Resource）</li>
 *   <li>不持久化 ToolCall 参数（工具调用的中间数据）</li>
 *   <li>ToolResponseMessage 也不持久化（工具返回结果只影响当次生成）</li>
 *   <li>只保留 4 种基础类型中的 USER / ASSISTANT / SYSTEM 三种文本消息</li>
 * </ul>
 */
@Component
public class RedisChatMemory implements ChatMemory {

    /**
     * key 前缀：chat_memory:{conversationId}
     * 与 chat_history:{type} 的用途区分开：这里存的是每个 chatId 的完整消息流。
     */
    private static final String KEY_PREFIX = "chat_memory:";

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public RedisChatMemory(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        // 独立 ObjectMapper：不需要 RedisConfig 里的 default typing，我们自己控制字段结构
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        String key = KEY_PREFIX + conversationId;
        List<String> serialized = new ArrayList<>(messages.size());
        for (Message m : messages) {
            String json = serialize(m);
            if (json != null) {
                serialized.add(json);
            }
        }
        if (!serialized.isEmpty()) {
            // RPUSH：追加到列表尾部，保持消息时间顺序
            stringRedisTemplate.opsForList().rightPushAll(key, serialized);
        }
    }

    @Override
    public List<Message> get(String conversationId, int lastN) {
        String key = KEY_PREFIX + conversationId;
        if (lastN <= 0) {
            return List.of();
        }
        // LRANGE key -lastN -1：取最后 lastN 条
        // 例如 lastN=100，取 [-100, -1] 即末尾 100 条；若列表少于 100 条则全返回
        List<String> raw = stringRedisTemplate.opsForList().range(key, -lastN, -1);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Message> result = new ArrayList<>(raw.size());
        for (String json : raw) {
            Message m = deserialize(json);
            if (m != null) {
                result.add(m);
            }
        }
        return result;
    }

    @Override
    public void clear(String conversationId) {
        stringRedisTemplate.delete(KEY_PREFIX + conversationId);
    }

    /**
     * 序列化：只保留 USER / ASSISTANT / SYSTEM 三种文本消息，其他类型跳过（返回 null）
     */
    private String serialize(Message message) {
        MessageType type = message.getMessageType();
        // 目前只持久化文本消息；ToolResponseMessage 等临时消息不进 Redis
        if (type != MessageType.USER && type != MessageType.ASSISTANT && type != MessageType.SYSTEM) {
            return null;
        }
        String text = message.getText();
        if (text == null) {
            text = "";
        }
        PersistedMessage dto = new PersistedMessage(type.getValue(), text, message.getMetadata());
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (JsonProcessingException e) {
            throw new BusinessException("序列化会话消息失败: " + e.getMessage(), e);
        }
    }

    /**
     * 反序列化：按 type 还原成对应的 Message 子类
     */
    private Message deserialize(String json) {
        PersistedMessage dto;
        try {
            dto = objectMapper.readValue(json, PersistedMessage.class);
        } catch (JsonProcessingException e) {
            // 单条损坏不影响整体读取，跳过
            return null;
        }
        MessageType type = MessageType.fromValue(dto.getType());
        String text = dto.getText() == null ? "" : dto.getText();
        return switch (type) {
            case USER -> new UserMessage(text);
            case ASSISTANT -> new AssistantMessage(text, dto.getMetadata() == null ? Map.of() : dto.getMetadata());
            case SYSTEM -> new SystemMessage(text);
            default -> null; // TOOL 等类型不还原
        };
    }

    /**
     * 内部 DTO：只保留会话记忆真正需要的字段。
     * 独立于 Spring AI 的 Message 类型，方便未来模型升级时兼容旧数据。
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class PersistedMessage {
        /** MessageType.getValue()：user / assistant / system */
        private String type;
        /** 消息文本内容 */
        private String text;
        /** 可选元数据 */
        private Map<String, Object> metadata;
    }
}
