package com.example.demo.repository;

import com.example.demo.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.Properties;

@Slf4j
@Component
@RequiredArgsConstructor
public class LocalPdfFileRepository implements FileRepository {

    private final VectorStore vectorStore;

    // 上传文件保存目录，可通过 app.pdf.upload-dir 配置，默认 uploads/pdf
    @Value("${app.pdf.upload-dir:uploads/pdf}")
    private String uploadDir;

    // 会话id 与 文件名的对应关系，方便查询会话历史时重新加载文件
    private final Properties chatFiles = new Properties();

    // 运行时持久化文件，保留在项目运行目录，与 README 描述保持一致
    private static final String META_FILE = "chat-pdf.properties";
    private static final String VECTOR_FILE = "chat-pdf.json";

    @Override
    public boolean save(String chatId, Resource resource) {
        // 1. 文件名安全处理：剔除路径信息、替换非法字符，防止路径遍历
        String rawFilename = resource.getFilename();
        String safeFilename = sanitizeFilename(rawFilename);

        // 2. 确保目标目录存在，并校验保存路径不能超出上传目录
        Path baseDir;
        Path target;
        try {
            baseDir = Path.of(uploadDir).toAbsolutePath().normalize();
            Files.createDirectories(baseDir);
            target = baseDir.resolve(safeFilename).normalize();
            if (!target.startsWith(baseDir)) {
                log.warn("Illegal upload path detected: chatId={}, filename={}", chatId, rawFilename);
                return false;
            }
        } catch (IOException e) {
            log.error("Failed to prepare upload directory.", e);
            return false;
        }

        // 3. 保存到本地磁盘，允许同 chatId 重新上传覆盖旧文件
        try {
            Files.copy(resource.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);//将上传的文件保存到本地
        } catch (IOException e) {
            log.error("Failed to save PDF resource.", e);
            return false;
        }

        // 4. 保存映射关系
        chatFiles.put(chatId, safeFilename);
        return true;
    }

    //下载相关文件
    @Override
    @Nullable
    public Resource getFile(String chatId) {
        String filename = chatFiles.getProperty(chatId);
        if (!StringUtils.hasText(filename)) {
            return null;
        }
        Path baseDir = Path.of(uploadDir).toAbsolutePath().normalize();
        Path target = baseDir.resolve(filename).normalize();
        if (!target.startsWith(baseDir)) {
            log.warn("Illegal download path detected: chatId={}, filename={}", chatId, filename);
            return null;
        }
        return new FileSystemResource(target);
    }


    @PostConstruct
    private void init() {
        //加载文件的方法
        FileSystemResource pdfResource = new FileSystemResource(META_FILE);
        if (pdfResource.exists()) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(pdfResource.getInputStream(), StandardCharsets.UTF_8))) {
                chatFiles.load(reader);
            } catch (IOException e) {
                throw new BusinessException("加载 PDF 映射文件失败", e);
            }
        }
        FileSystemResource vectorResource = new FileSystemResource(VECTOR_FILE);
        if (vectorResource.exists()) {
            SimpleVectorStore simpleVectorStore = (SimpleVectorStore) vectorStore;
            simpleVectorStore.load(vectorResource);
        }
    }

    @PreDestroy
    private void persistent() {
        try {
            chatFiles.store(Files.newBufferedWriter(Path.of(META_FILE), StandardCharsets.UTF_8),
                    LocalDateTime.now().toString());
            SimpleVectorStore simpleVectorStore = (SimpleVectorStore) vectorStore;
            simpleVectorStore.save(new java.io.File(VECTOR_FILE));
        } catch (IOException e) {
            throw new BusinessException("持久化 PDF 数据失败", e);
        }
    }

    /**
     * 文件名安全化：去掉路径、替换 Windows 非法字符，避免路径遍历与文件系统异常
     */
    private String sanitizeFilename(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "unnamed.pdf";
        }
        String baseName = StringUtils.getFilename(filename);
        if (baseName == null || baseName.isBlank()) {
            return "unnamed.pdf";
        }
        // 替换 Windows / Unix 文件系统中常见的非法字符
        String safe = baseName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return safe.isEmpty() ? "unnamed.pdf" : safe;
    }
}
