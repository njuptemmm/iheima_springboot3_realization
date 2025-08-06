//这一部分功能是记录每一个对话的chatId，使其针对每一个chatId都拥有独立的记忆
//同时，我们还能记忆每一个chatId的历史记录，也就是传给前端之后能够立刻进行使用操作

//具体的功能是能够在开启一个对话之后，将相关的历史信息与访问这个对话的token进行联系
//从而实现将我们前面问答中使用的数据储存在内存之中，并且作为下一次对话中的提示词

package com.example.demo.controller;
import com.example.demo.entity.vo.MessageVO;
import com.example.demo.repository.ChatHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RequiredArgsConstructor
@RestController
@RequestMapping("/ai/history")
public class ChatHistoryController {

    private final ChatHistoryRepository chatHistoryRepository;

    private final ChatMemory chatMemory;

    //根据类型来查询所有的用户ID
    @GetMapping("/{type}")
    public List<String> getChatIds(@PathVariable("type") String type){
        return chatHistoryRepository.getChatIds(type);
    }

    //根据会话的ID查询其中详细的聊天记录
    @GetMapping("/{type}/{chatId}")
    public List<MessageVO> getChatHistory(@PathVariable("type") String type, @PathVariable("chatId") String chatId) {
        List<Message>messages =chatMemory.get(chatId,Integer.MAX_VALUE);//按照视频教学的结果来说这里应该是有一个数值用来用表示返回的文本量的，但是查询文件发现并没有相关的限制？
        //使用的springboot中保存了一个方法能够使用用户的ID来查询会话的记录，也就是我们在这里实现的操作
        if(messages==null){
            return List.of();
        }
        return messages.stream()
                .map(MessageVO::new)
                .toList();
        //这里的操作是将查询到的消息转换为MessageVO对象
    }

}
