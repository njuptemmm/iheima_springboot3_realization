package com.example.demo.repository;

import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.*;


@Component
public class InMemoryChatHistoryRepository implements ChatHistoryRepository {
    //思路：使用一个map来存储值和对应的数据类型
    private final Map<String, List<String>> chatHistory = new HashMap<>();//这里是将数据存储在内存中

    @Override
    public void save(String type, String chatId) {
        /*
        //这里实现的方法是判断这个类型是否出现过，如果没有出现过则有奥添加这种类型
        //判断map集合中有没有对应类型的集合，如果没有就添加一个集合
        if(!chatHistory.containsKey(type)) {
            chatHistory.put(type, List.of(chatId));
        }
        List<String> chatIds = chatHistory.get(type);
         */
        List<String> chatIds = chatHistory.computeIfAbsent(type, k -> new ArrayList<>());//调用其中的方法从而实现上面的效果
        if (chatIds.contains(chatId)) {
            return; // 如果已经这个chatId已经在内存中存在，则不添加
        }
        chatIds.add(chatId);
    }

    @Override
    public List<String> getChatIds(String type) {
        /*
        List<String> chatIds = chatHistory.get(type);
        return chatIds == null ? new ArrayList<>() : chatIds;
        */
        return chatHistory.getOrDefault(type,List.of());//ArayList是一个空的集合,可以直接简化为List.of()
    }

}
