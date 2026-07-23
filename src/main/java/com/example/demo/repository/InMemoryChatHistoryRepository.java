package com.example.demo.repository;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;


/**
 * ChatHistoryRepository 的内存版实现 —— 学习对照，运行时不会被注入使用。
 *
 * <p>Redis 版本 {@link RedisChatHistoryRepository} 加了 {@code @Primary} 优先注入，
 * 这个内存版仍带 {@code @Component} 被 Spring 扫描到，只作为对照参考保留：
 * <ul>
 *   <li>看两种实现的写法差异（Set vs ConcurrentHashMap+CopyOnWriteArrayList）</li>
 *   <li>如果想临时切回内存版，把 Redis 版的 {@code @Primary} 移到这里即可</li>
 *   <li>写单元测试时可以显式注入这个内存版，避免依赖 Redis</li>
 * </ul>
 *
 * <p>局限：服务重启后所有 chatId 列表清零，这就是引入 Redis 版本的直接原因。
 */
@Component
public class InMemoryChatHistoryRepository implements ChatHistoryRepository {
    //思路：使用一个map来存储值和对应的数据类型
    // 使用线程安全的 ConcurrentHashMap + CopyOnWriteArrayList，避免并发请求导致数据不一致
    private final Map<String, List<String>> chatHistory = new ConcurrentHashMap<>();//这里是将数据存储在内存中

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
        List<String> chatIds = chatHistory.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>());//调用其中的方法从而实现上面的效果
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
        List<String> chatIds = chatHistory.get(type);
        // 返回不可变副本，防止外部修改内部状态
        return chatIds == null ? List.of() : List.copyOf(chatIds);
    }

}
