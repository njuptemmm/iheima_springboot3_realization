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
            this.writeToVectorStore(file.getResource());
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

    private void writeToVectorStore(Resource resource) {
        // 1.创建PDF的读取器
        PagePdfDocumentReader reader = new PagePdfDocumentReader(
                resource, // 文件源
                PdfDocumentReaderConfig.builder()
                        .withPageExtractedTextFormatter(ExtractedTextFormatter.defaults())
                        .withPagesPerDocument(1) // 每1页PDF作为一个Document
                        .build()
        );
        // 2.读取PDF文档，拆分为Document
        List<Document> documents = reader.read();
        // 3.写入向量库
        vectorStore.add(documents);
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
