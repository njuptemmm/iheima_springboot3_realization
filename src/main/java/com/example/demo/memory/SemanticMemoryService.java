package com.example.demo.memory;

import com.example.demo.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 语义长期记忆服务：把对话中的事实性陈述提炼、向量化、持久化，支持跨会话语义召回。
 *
 * <p>双层记忆架构：
 * <ul>
 *   <li><b>短期记忆</b>：{@link ChatMemory}（Redis 实现）保存会话原文，服务重启后可恢复</li>
 *   <li><b>长期记忆</b>：本服务把"事实"（偏好、背景、目标、习惯）提炼成向量，
 *       下次用户换 chatId 后仍能根据语义相似度召回</li>
 * </ul>
 *
 * <p>持久化：独立的 {@code semantic-memory.json}，与 PDF 向量库的 {@code chat-pdf.json} 隔离。
 */
@Slf4j
@Service
public class SemanticMemoryService {

    /** 向量库持久化文件 */
    private static final String MEMORY_FILE = "semantic-memory.json";
    /** 从 ChatMemory 读取最近多少条消息用于事实提取 */
    private static final int EXTRACT_HISTORY_SIZE = 20;
    /** 每个事实向量化时允许的最大字符数 */
    private static final int FACT_MAX_LENGTH = 500;
    /** 提取事实 prompt 中对话的最大字符数，防止 token 超限 */
    private static final int DIALOGUE_MAX_LENGTH = 2000;
    /** 解析 LLM 返回的事实列表：以 - 或 * 开头的行 */
    private static final Pattern FACT_PATTERN = Pattern.compile("^[\\s]*[-*•][\\s]+(.+)$", Pattern.MULTILINE);

    private final SimpleVectorStore memoryStore;
    private final ChatModel chatModel;
    private final ChatMemory chatMemory;

    @Value("${app.semantic-memory.enabled:true}")
    private boolean enabled;

    public SemanticMemoryService(OpenAiEmbeddingModel embeddingModel, ChatModel chatModel, ChatMemory chatMemory) {
        this.memoryStore = SimpleVectorStore.builder(embeddingModel).build();
        this.chatModel = chatModel;
        this.chatMemory = chatMemory;
    }

    /**
     * 启动时加载已持久化的语义记忆。
     */
    @PostConstruct
    public void load() {
        Resource resource = new FileSystemResource(MEMORY_FILE);
        if (resource.exists()) {
            try {
                memoryStore.load(resource);
                log.info("[SemanticMemory] 已加载持久化记忆：{}", MEMORY_FILE);
            } catch (Exception e) {
                log.warn("[SemanticMemory] 加载持久化记忆失败：{}", e.getMessage());
            }
        }
    }

    /**
     * 关闭时保存语义记忆到本地文件。
     */
    @PreDestroy
    public void save() {
        try {
            memoryStore.save(new File(MEMORY_FILE));
            log.info("[SemanticMemory] 已保存记忆到：{}", MEMORY_FILE);
        } catch (Exception e) {
            log.error("[SemanticMemory] 保存记忆失败：{}", e.getMessage(), e);
            throw new BusinessException("语义记忆持久化失败", e);
        }
    }

    /**
     * 语义召回：根据查询找最相关的长期记忆。
     */
    public List<Document> recall(String query, int topK) {
        if (!enabled) {
            return List.of();
        }
        return memoryStore.similaritySearch(
                SearchRequest.builder()
                        .query(query)
                        .topK(topK)
                        .similarityThresholdAll()
                        .build()
        );
    }

    /**
     * 从指定会话的最近消息中提取事实并保存。
     *
     * <p>由 {@link com.example.demo.controller.ChatController} 在流式响应结束后调用，
     * 避免在 Advisor 的 {@code after()} 里对每块 chunk 重复触发。
     */
    public void extractAndStore(String chatId) {
        if (!enabled) {
            return;
        }
        try {
            List<Message> messages = chatMemory.get(chatId, EXTRACT_HISTORY_SIZE);
            if (messages == null || messages.size() < 2) {
                return;
            }
            // 取最后一条 USER 和最后一条 ASSISTANT 作为提取单元
            String userText = findLastMessageOfType(messages, MessageType.USER);
            String assistantText = findLastMessageOfType(messages, MessageType.ASSISTANT);
            if (userText == null || assistantText == null) {
                return;
            }
            List<String> facts = extractFacts(userText, assistantText);
            if (facts.isEmpty()) {
                return;
            }
            for (String fact : facts) {
                storeFact(fact, chatId);
            }
            log.info("[SemanticMemory] chatId={} 提取 {} 条事实", chatId, facts.size());
        } catch (Exception e) {
            log.warn("[SemanticMemory] chatId={} 提取事实失败：{}", chatId, e.getMessage());
        }
    }

    /**
     * 用 LLM 从一对 QA 中提取事实性记忆。
     */
    private List<String> extractFacts(String userText, String assistantText) {
        String dialogue = "User: " + truncate(userText, DIALOGUE_MAX_LENGTH) + "\nAssistant: " + truncate(assistantText, DIALOGUE_MAX_LENGTH);
        String prompt = """
                请从以下对话中提取关于用户的"事实性记忆"（偏好、背景、目标、习惯、计划等）。
                只提取长期有价值、跨会话仍能使用的信息。
                每条事实用一句话表达，以 "- " 开头，每行一条。
                如果没有任何值得记忆的事实，只回复"无"。

                %s

                事实：""".formatted(dialogue);

        String response = chatModel.call(prompt);
        if (response == null || response.isBlank()) {
            return List.of();
        }
        String trimmed = response.trim();
        if (trimmed.startsWith("无") || trimmed.toLowerCase().startsWith("none")) {
            return List.of();
        }

        Matcher matcher = FACT_PATTERN.matcher(trimmed);
        List<String> facts = new ArrayList<>();
        while (matcher.find()) {
            String fact = matcher.group(1).trim();
            if (fact.length() > 5) { // 过滤过短的无意义行
                facts.add(fact);
            }
        }
        return facts;
    }

    /**
     * 把事实存入向量库。
     */
    private void storeFact(String fact, String chatId) {
        String normalized = truncate(fact, FACT_MAX_LENGTH);
        Document doc = new Document(normalized, Map.of(
                "chatId", chatId,
                "timestamp", Instant.now().toString(),
                "source", "semantic_memory"
        ));
        memoryStore.add(List.of(doc));
    }

    private String findLastMessageOfType(List<Message> messages, MessageType type) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m.getMessageType() == type) {
                return m.getText();
            }
        }
        return null;
    }

    private String truncate(String text, int maxLength) {
        if (text == null || text.length() <= maxLength) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxLength);
    }

    /**
     * 格式化召回的记忆，用于注入 system prompt。
     */
    public String formatFacts(List<Document> docs) {
        if (docs == null || docs.isEmpty()) {
            return "";
        }
        return docs.stream()
                .map(Document::getText)
                .filter(text -> text != null && !text.isBlank())
                .distinct()
                .map(text -> "- " + text.trim())
                .collect(Collectors.joining("\n"));
    }

    public boolean isEnabled() {
        return enabled;
    }
}