package com.example.demo.repository;

import org.springframework.core.io.Resource;
//Resource是Spring框架提供的一个接口，用于表示资源的抽象。它可以表示文件、URL、类路径资源等多种类型的资源。

public interface FileRepository {
    /**
     * 保存文件,还要记录chatId与文件的映射关系
     * @param chatId 会话id
     * @param resource 文件
     * @return 上传成功，返回true； 否则返回false
     */
    boolean save(String chatId, Resource resource);

    /**
     * 根据chatId获取文件
     * @param chatId 会话id
     * @return 找到的文件
     */
    Resource getFile(String chatId);
}
