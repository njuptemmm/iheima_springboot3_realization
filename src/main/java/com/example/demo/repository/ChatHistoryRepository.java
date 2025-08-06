//为了需要完成记录历史的效果，于是我们是需要一个chatID和相关的信息
//ChatID是基于业务类型进行保存

package com.example.demo.repository;

import java.util.List;

public interface ChatHistoryRepository {
    /**
     * 保存对话历史
     *@param type 业务类型，如：chat、service、pdf
     *@param chatId 对话类型
     */
    void save(String type,String chatId);

    /**
     * 获取对话ID列表
     * @param type 业务类型，如：chat、service、pdf
     * @return 会话ID列表
     */
    List<String> getChatIds(String type);
}
