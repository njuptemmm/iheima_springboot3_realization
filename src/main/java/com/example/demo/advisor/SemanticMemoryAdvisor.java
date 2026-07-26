package com.example.demo.advisor;

import com.example.demo.memory.SemanticMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.document.Document;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 语义长期记忆 Advisor：请求前召回相关事实并注入 system prompt。
 *
 * <p>事实提取（extract）不放在 {@code after()} 中，因为流式响应下
 * {@link BaseAdvisor#aroundStream} 会对每一块 chunk 调用一次 {@code after()}，
 * 导致重复提取和 token 浪费。提取逻辑改由 {@link com.example.demo.controller.ChatController}
 * 在流式响应结束后显式触发 {@link SemanticMemoryService#extractAndStore(String)}。
 */
@Slf4j
public class SemanticMemoryAdvisor implements BaseAdvisor {

    private static final String NAME = "semantic_memory";
    private static final int RECALL_TOP_K = 3;

    private final SemanticMemoryService semanticMemoryService;

    public SemanticMemoryAdvisor(SemanticMemoryService semanticMemoryService) {
        this.semanticMemoryService = semanticMemoryService;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int getOrder() {
        // 在 MessageChatMemoryAdvisor 之后、模型之前执行，
        // 这样既能看到完整历史，又能在 system prompt 中注入长期记忆
        return 0;
    }

    @Override
    public AdvisedRequest before(AdvisedRequest request) {
        String userQuery = request.userText();
        if (!StringUtils.hasText(userQuery)) {
            return request;
        }
        if (!semanticMemoryService.isEnabled()) {
            return request;
        }

        // 1. 语义召回：根据用户问题找最相关的长期记忆
        List<Document> memories = semanticMemoryService.recall(userQuery, RECALL_TOP_K);
        if (memories == null || memories.isEmpty()) {
            log.debug("[SemanticMemory] 未召回相关记忆");
            return request;
        }

        // 2. 格式化记忆
        String facts = semanticMemoryService.formatFacts(memories);
        log.debug("[SemanticMemory] 召回 {} 条记忆\n{}", memories.size(), facts);

        // 3. 把记忆注入 system prompt，让模型知道"我之前了解你这些信息"
        String originalSystem = request.systemText();
        String memoryBlock = """

                【你的长期记忆】
                你在之前的对话中了解到关于用户的以下事实，回答时请结合这些事实：
                %s
                """.formatted(facts);

        String newSystemText = StringUtils.hasText(originalSystem)
                ? originalSystem + memoryBlock
                : memoryBlock;

        return AdvisedRequest.from(request)
                .systemText(newSystemText)
                .build();
    }

    @Override
    public AdvisedResponse after(AdvisedResponse advisedResponse) {
        // 事实提取由 ChatController 在流式响应结束后触发，避免重复
        return advisedResponse;
    }
}