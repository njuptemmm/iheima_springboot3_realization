package com.example.demo.controller;

import com.example.demo.entity.vo.Result;
import com.example.demo.exception.BusinessException;
import com.example.demo.repository.ChatHistoryRepository;
import com.example.demo.repository.FileRepository;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.ai.reader.pdf.config.PdfDocumentReaderConfig;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY;
import static org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor.FILTER_EXPRESSION;

@Slf4j
@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/pdf")
public class PdfController {

    private final FileRepository fileRepository;

    private final VectorStore vectorStore;

    private final ChatClient pdfChatClient;

    private final ChatHistoryRepository chatHistoryRepository;

    @RequestMapping(value = "/chat", method = {RequestMethod.GET, RequestMethod.POST}, produces = "text/html;charset=utf-8")
    public Flux<String> chat(
            @RequestParam("prompt") @NotBlank String prompt,
            @RequestParam("chatId") @NotBlank String chatId) {
        // 1.找到会话文件
        Resource file = fileRepository.getFile(chatId);
        if (file == null || !file.exists()) {
            // 文件不存在，不回答
            throw new BusinessException("会话文件不存在！");
        }
        // 2.保存会话id
        chatHistoryRepository.save("pdf", chatId);
        // 3.使用 FilterExpressionBuilder 安全构建过滤表达式，避免文件名中的单引号等破坏表达式
        String filename = Objects.requireNonNull(file.getFilename());
        String filterExpression = new FilterExpressionBuilder()
                .eq("file_name", filename)
                .build()
                .toString();
        // 4.请求模型
        return pdfChatClient.prompt()
                .user(prompt)
                .advisors(a -> a.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId))
                .advisors(a -> a.param(FILTER_EXPRESSION, filterExpression))
                .stream()
                .content();
    }

    /**
     * 文件上传
     */
    @PostMapping("/upload/{chatId}")
    public Result uploadPdf(@PathVariable @NotBlank String chatId, @RequestParam("file") MultipartFile file) {
        try {
            // 1. 校验文件是否为PDF格式
            if (!isPdf(file)) {
                return Result.fail("只能上传PDF文件！");
            }
            // 2.保存文件
            boolean success = fileRepository.save(chatId, file.getResource());
            if (!success) {
                return Result.fail("保存文件失败！");
            }
            // 3.写入向量库
            this.writeToVectorStore(file.getResource(), file.getOriginalFilename());
            return Result.ok();
        } catch (Exception e) {
            log.error("Failed to upload PDF.", e);
            return Result.fail("上传文件失败！");
        }
    }

    /**
     * 文件下载
     */
    @GetMapping("/file/{chatId}")
    public ResponseEntity<Resource> download(@PathVariable("chatId") @NotBlank String chatId) throws IOException {
        // 1.读取文件
        Resource resource = fileRepository.getFile(chatId);
        if (resource == null || !resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        // 2.文件名编码，写入响应头
        String filename = URLEncoder.encode(Objects.requireNonNull(resource.getFilename()), StandardCharsets.UTF_8);
        // 3.返回文件
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(resource);
    }

    /**
     * PDF 文本摄入：按页粗切 → TokenTextSplitter 语义细切 → 写入向量库。
     *
     * <p>切块策略说明：
     * <ul>
     *   <li>第一级（粗切）：{@link PagePdfDocumentReader} 按 PDF 页边界切分，保留 {@code page_number} 元数据（可溯源）</li>
     *   <li>第二级（细切）：{@link TokenTextSplitter} 按 token 数再切，chunkSize=500 token，minChunkSizeChars=350</li>
     *   <li>每个 chunk 继承原页的 {@code file_name} 和 {@code page_number} 元数据，确保 FilterExpression 文件级过滤生效</li>
     * </ul>
     *
     * <p>为什么不用 overlap？
     * Spring AI 1.0.0-M6 的 TokenTextSplitter 暂不支持 overlap 参数（M7+ 版本加入），
     * 但通过 chunkSize=500 + minChunkSizeChars=350 的宽松下限，相邻 chunk 在语义边界处
     * 自然保留了一定的上下文连续性。面试时可讲清楚"滑动窗口"原理，指出当前版本用的
     * 是"固定大小分块 + 文档结构感知（页码元数据）"的混合策略。
     */
    private void writeToVectorStore(Resource resource, String originalFilename) {
        // 1. 第一级切分：按 PDF 页边界粗切（保留 page_number 元数据，实现可溯源）
        PagePdfDocumentReader reader = new PagePdfDocumentReader(
                resource,
                PdfDocumentReaderConfig.builder()
                        .withPageExtractedTextFormatter(ExtractedTextFormatter.defaults())
                        .withPagesPerDocument(1) // 每1页PDF作为一个Document
                        .build()
        );
        List<Document> pageDocs = reader.read();
        log.info("PDF 按页切分完成，共 {} 页，文件名：{}", pageDocs.size(), originalFilename);

        // 2. 补上 file_name 元数据（修复 FilterExpression 过滤不生效的 bug）
        //    之前 PagePdfDocumentReader 生成的 Document 没有 file_name，
        //    导致 PdfController.chat() 里的 FilterExpressionBuilder.eq("file_name", ...) 匹配不到任何文档
        for (Document doc : pageDocs) {
            doc.getMetadata().put("file_name", originalFilename);
        }

        // 3. 第二级切分：TokenTextSplitter 按 token 数细切
        //    chunkSize=500：每个 chunk 约 500 tokens（平衡检索粒度与语义完整性）
        //    minChunkSizeChars=350：最小 350 字符，太短的 chunk 合并到前一个
        //    minChunkLengthToEmbed=50：少于 50 tokens 的 chunk 不单独入向量库（噪声）
        //    maxNumChunks=100：每页最多 100 个 chunk（防止异常大的页面撑爆向量库）
        //    keepSeparator=true：保留段落分隔符，维护语义边界
        //    生成的 chunk 自动继承原 Document 的 metadata（file_name + page_number）
        TokenTextSplitter splitter = TokenTextSplitter.builder()
                .withChunkSize(500)
                .withMinChunkSizeChars(350)
                .withMinChunkLengthToEmbed(50)
                .withMaxNumChunks(100)
                .withKeepSeparator(true)
                .build();
        List<Document> chunks = splitter.apply(pageDocs);
        log.info("TokenTextSplitter 切分完成，{} 页 → {} 个 chunk（平均 {}/页）",
                pageDocs.size(), chunks.size(),
                pageDocs.isEmpty() ? 0 : chunks.size() / pageDocs.size());

        // 4. 写入向量库
        vectorStore.add(chunks);
        log.info("向量库写入完成，共 {} 个 Document", chunks.size());
    }

    /**
     * 校验上传文件是否为 PDF
     */
    private boolean isPdf(MultipartFile file) {
        return file != null
                && !file.isEmpty()
                && "application/pdf".equals(file.getContentType())
                && StringUtils.endsWithIgnoreCase(file.getOriginalFilename(), ".pdf");
    }
}
