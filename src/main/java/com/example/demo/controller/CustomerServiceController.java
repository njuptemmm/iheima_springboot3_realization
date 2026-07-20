//这里我们要求前端的请求路径为 /ai/chat

package com.example.demo.controller;

import com.example.demo.repository.ChatHistoryRepository;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import static org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY;

@Validated
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai")//这里主要就是在设置路径的时候要求要注意
public class CustomerServiceController {

    private final ChatClient serviceChatClient;

    private final ChatHistoryRepository chatHistoryRepository;

    @RequestMapping(value = "/service", method = {RequestMethod.GET, RequestMethod.POST}, produces = "text/html; charset=UTF-8")
    public Flux<String> service(
            @RequestParam("prompt") @NotBlank String prompt,
            @RequestParam("chatId") @NotBlank String chatId) {
        //1、保存会话ID
        chatHistoryRepository.save("service", chatId);//区分是客服的对话历史还是chat的对话历史
        //2、请求模型
        return serviceChatClient.prompt()
                .user(prompt)
                .advisors(a -> a.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId))//导入一个前端的ID，从而实现
                .stream()
                .content();
    }
}
