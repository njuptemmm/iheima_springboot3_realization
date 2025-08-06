//这里我们要求前端的请求路径为 /ai/chat

package com.example.demo.controller;

import com.example.demo.repository.ChatHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.model.Media;
import org.springframework.util.MimeType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Objects;

import static org.springframework.ai.chat.client.advisor.AbstractChatMemoryAdvisor.CHAT_MEMORY_CONVERSATION_ID_KEY;

@CrossOrigin("*") //允许所有来源的跨域请求
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai")//这里主要就是在设置路径的时候要求要注意
public class  ChatController {

    private final ChatClient chatClient;

    private final ChatHistoryRepository chatHistoryRepository;

    /*
    //使用非流式的方法来进行输出
    @RequestMapping("/chat")
    public String chat(String prompt) {
        return chatClient.prompt()
                .user(prompt)
                .call()
                .content();
    }
    */
    //使用流式的方法来使用使用这个进行输出：
    @RequestMapping(value = "/chat", produces = "text/html; charset=UTF-8")
    public Flux<String> chat(
            @RequestParam("prompt") String prompt,
            @RequestParam("chatId") String chatId,
            @RequestParam(value="files",required = false) List<MultipartFile> files)//多文件的集合
    {
        //1、保存会话ID
        chatHistoryRepository.save("chat", chatId);//只要产生对话，就将对话ID保存下来
        //2、请求模型
        if(files==null || files.isEmpty()){
            //纯文本聊天
            return textChat(prompt,chatId);
        }
        else{
            //文件聊天
            return multiModelChat(prompt, chatId, files);
        }

    }

    private Flux<String> textChat(String prompt, String chatId) {
        return chatClient.prompt()
                .user(prompt)
                //导入一个前端的ID，从而实现能够记录日志的计算
                .advisors(a->a.param(CHAT_MEMORY_CONVERSATION_ID_KEY,chatId))
                .stream()
                .content();
    }

    private Flux<String> multiModelChat(String prompt, String chatId, List<MultipartFile> files) {
        // 1.解析多媒体
        List<Media> medias = files.stream()
                .map(file -> new Media(
                                MimeType.valueOf(Objects.requireNonNull(file.getContentType())),
                                file.getResource()
                        )
                )
                .toList();
        // 2.请求模型
        return chatClient.prompt()
                .user(p -> p.text(prompt).media(medias.toArray(Media[]::new)))
                .advisors(a -> a.param(CHAT_MEMORY_CONVERSATION_ID_KEY, chatId))
                .stream()
                .content();
    }

}
