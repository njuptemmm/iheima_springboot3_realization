package com.example.demo.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.advisor.api.AdvisedRequest;
import org.springframework.ai.chat.client.advisor.api.AdvisedResponse;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor.FILTER_EXPRESSION;

/**
 * 两级排序 RAG Advisor：粗排（向量余弦相似度）→ 精排（LLM 语义打分）。
 *
 * <p>替换 Spring AI 内置的 {@link org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor}，
 * 在检索阶段多做两步：
 * <ol>
 *   <li><b>粗排</b>：向量检索 topK=8，用余弦相似度快速召回候选文档</li>
 *   <li><b>精排</b>：对每个候选让 ChatModel 打 1-10 分，取 top 3 注入 prompt</li>
 * </ol>
 *
 * <p>面试话术：粗排用向量余弦做高召回（快但语义理解弱），精排用 LLM 语义判断做高精度
 * （慢但准确）。两级分工，兼顾速度与质量。
 *
 * <p>过滤支持：通过 advisor param {@code FILTER_EXPRESSION} 传入过滤表达式，
 * 与 QuestionAnswerAdvisor 的用法完全兼容。
 */
@Slf4j
public class RerankAdvisor implements BaseAdvisor {

    private static final String NAME = "rerank";
    /** 粗排召回数量：多召回一些候选，给精排留余量 */
    private static final int RECALL_SIZE = 8;
    /** 精排后保留数：LLM 打分后取最相关的几个注入 prompt */
    private static final int RERANK_TOP = 3;
    /** 打分时截断文档长度（字符），防止 token 超限 */
    private static final int SCORE_DOC_MAX_LENGTH = 1500;
    /** 打分结果解析正则：匹配 "8" 或 "Rating: 8" 或 "分数：8" */
    private static final Pattern SCORE_PATTERN = Pattern.compile("(\\d+)\\s*$", Pattern.MULTILINE);

    private final VectorStore vectorStore;
    private final ChatModel chatModel;

    public RerankAdvisor(VectorStore vectorStore, ChatModel chatModel) {
        this.vectorStore = vectorStore;
        this.chatModel = chatModel;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public int getOrder() {
        return 0;
    }

    /**
     * 请求前：粗排 + 精排 + 注入上下文。
     */
    @Override
    public AdvisedRequest before(AdvisedRequest request) {
        String userQuery = request.userText();
        if (!StringUtils.hasText(userQuery)) {
            return request;
        }

        // 1. 获取过滤表达式（与 QuestionAnswerAdvisor 兼容）
        String filterExpression = null;
        Map<String, Object> advisorParams = request.advisorParams();
        if (advisorParams != null && advisorParams.containsKey(FILTER_EXPRESSION)) {
            filterExpression = (String) advisorParams.get(FILTER_EXPRESSION);
        }

        // 2. 粗排：向量检索 topK=RECALL_SIZE
        SearchRequest.Builder searchBuilder = SearchRequest.builder()
                .query(userQuery)
                .topK(RECALL_SIZE)
                .similarityThresholdAll();
        if (filterExpression != null) {
            searchBuilder.filterExpression(filterExpression);
        }
        List<Document> candidates = vectorStore.similaritySearch(searchBuilder.build());
        log.debug("[Rerank] 粗排召回 {} 个候选文档", candidates.size());

        if (candidates.isEmpty()) {
            log.debug("[Rerank] 无候选文档，跳过精排");
            return request;
        }

        // 3. 精排：LLM 对每个候选打分
        List<ScoredDocument> scored = rerank(candidates, userQuery);
        if (scored.isEmpty()) {
            return request;
        }

        // 4. 取 top RERANK_TOP 注入用户消息
        List<ScoredDocument> topDocs = scored.stream()
                .limit(RERANK_TOP)
                .toList();

        String context = formatContext(topDocs);
        String augmentedUserText = userQuery + "\n\n" + context;
        log.info("[Rerank] 精排完成，top {} 得分：{}",
                RERANK_TOP,
                topDocs.stream().map(sd -> String.format("%.1f", sd.score)).collect(Collectors.joining(", ")));

        return AdvisedRequest.from(request)
                .userText(augmentedUserText)
                .build();
    }

    @Override
    public AdvisedResponse after(AdvisedResponse advisedResponse) {
        return advisedResponse;
    }

    /**
     * 对每个候选文档，让 LLM 打分（1-10），按分数降序排列。
     */
    private List<ScoredDocument> rerank(List<Document> candidates, String query) {
        List<ScoredDocument> scored = new ArrayList<>(candidates.size());

        for (int i = 0; i < candidates.size(); i++) {
            Document doc = candidates.get(i);
            String content = doc.getText();
            if (content == null || content.isBlank()) {
                continue;
            }
            // 截断过长文档，只取前 SCORE_DOC_MAX_LENGTH 字符用于打分
            String truncated = content.length() > SCORE_DOC_MAX_LENGTH
                    ? content.substring(0, SCORE_DOC_MAX_LENGTH)
                    : content;

            // 构造打分 prompt
            String scorePrompt = """
                    请评估以下文档与用户查询的相关性，给出 1-10 分（1=完全无关，10=高度相关）。
                    只回复数字，不要有任何其他文字。

                    用户查询：%s

                    文档内容：
                    %s

                    相关性分数：""".formatted(query, truncated);

            try {
                String response = chatModel.call(scorePrompt);
                double score = parseScore(response);
                scored.add(new ScoredDocument(doc, score, i));
                log.debug("[Rerank] 候选 #{} 得分：{}（原始响应：{}）", i, score, response.trim());
            } catch (Exception e) {
                log.warn("[Rerank] 候选 #{} 打分失败，跳过：{}", i, e.getMessage());
                // 打分失败给一个默认低分，不阻塞整体流程
                scored.add(new ScoredDocument(doc, 1.0, i));
            }
        }

        scored.sort(Comparator.comparingDouble(ScoredDocument::score).reversed());
        return scored;
    }

    /**
     * 从 LLM 响应中解析分数。
     * 支持格式：纯数字 "8"、"8."、"分数：8"、"Rating: 8"、"8/10" 等。
     */
    private double parseScore(String response) {
        if (response == null || response.isBlank()) {
            return 1.0;
        }
        String trimmed = response.trim();
        // 尝试提取最后的数字
        Matcher matcher = SCORE_PATTERN.matcher(trimmed);
        if (matcher.find()) {
            try {
                double score = Double.parseDouble(matcher.group(1));
                // 限制在 1-10 范围内
                return Math.max(1.0, Math.min(10.0, score));
            } catch (NumberFormatException e) {
                // fall through
            }
        }
        // 尝试直接解析整个字符串
        try {
            double score = Double.parseDouble(trimmed.replace("/10", "").trim());
            return Math.max(1.0, Math.min(10.0, score));
        } catch (NumberFormatException e) {
            log.debug("[Rerank] 无法解析分数：{}，默认 1.0", trimmed);
            return 1.0;
        }
    }

    /**
     * 将精排后的文档格式化为上下文文本。
     * 包含页码信息（page_number metadata），便于可溯源。
     */
    private String formatContext(List<ScoredDocument> docs) {
        StringBuilder sb = new StringBuilder();
        sb.append("[参考文档]（以下内容来自知识库，按相关性排序）\n\n");
        for (int i = 0; i < docs.size(); i++) {
            ScoredDocument sd = docs.get(i);
            Document doc = sd.doc;
            Object pageNum = doc.getMetadata().getOrDefault("page_number", "?");
            sb.append("--- 文档片段 #").append(i + 1)
                    .append("（来源页：").append(pageNum)
                    .append("，相关度：").append(String.format("%.1f", sd.score)).append("）---\n");
            sb.append(doc.getText()).append("\n\n");
        }
        return sb.toString();
    }

    /**
     * 带分数的文档包装类。
     */
    private record ScoredDocument(Document doc, double score, int originalIndex) {
    }
}