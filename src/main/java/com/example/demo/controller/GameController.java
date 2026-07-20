//这里我们要求前端的请求路径为 /ai/game

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
@RequestMapping("/ai")
public class GameController {

    private final ChatClient gameChatClient;

    private final ChatHistoryRepository chatHistoryRepository;

    @RequestMapping(value = "/game", method = {RequestMethod.GET, RequestMethod.POST}, produces = "text/html; charset=UTF-8")
    public Flux<String> chat(
            @RequestParam("prompt") @NotBlank String prompt,
            @RequestParam("chatId") @NotBlank String chatId) {
        // 保存游戏会话ID，方便后续查询历史
        chatHistoryRepository.save("game", chatId);
        return gameChatClient.prompt()
                .user(prompt)
                .advisors(a->a.param(CHAT_MEMORY_CONVERSATION_ID_KEY,chatId))//导入一个前端的ID，从而实现
                .stream()
                .content();
    }
}
