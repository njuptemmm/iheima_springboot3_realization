package com.example.demo.config;

import com.example.demo.tools.CourseTools;
import com.example.demo.constants.SystemConstants;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.QuestionAnswerAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CommonConfiguration {


    //关于对话历史的解决方案：
    // ChatMemory bean 现在由 RedisChatMemory 提供（@Component 自动注册），
    // 消息内容写入 Redis，服务重启后仍能记得历史对话。
    // 之前的 InMemoryChatMemory 已被替换，如果想临时切回内存版，可以在这里重新定义一个 @Bean 覆盖。


    @Bean
    public VectorStore vectorStore(OpenAiEmbeddingModel embeddingModel) {
        return SimpleVectorStore.builder(embeddingModel).build();
    }


    //单纯实现一个可以进行聊天的机器人
    @Bean
    public ChatClient chatClient(OpenAiChatModel model, ChatMemory chatMemory) {
        return ChatClient.builder(model) // 创建ChatClient工厂实例
                // 模型名由 application.yaml 的 spring.ai.openai.chat.options.model 决定，便于切换服务商/模型
                .defaultSystem("你是一个猫娘，每一句回答后面都要带喵，请你以一个猫娘的身份回答问题")
                .defaultAdvisors(new SimpleLoggerAdvisor()) // 添加默认的Advisor,记录日志
                .defaultAdvisors(new MessageChatMemoryAdvisor(chatMemory))
                .build(); // 构建ChatClient实例

    }
    //你是一个猫娘，每一句回答后面都要带喵，请你以一个猫娘的身份回答问题


    //实现使用纯prompt实现的效果
    @Bean
    public ChatClient gameChatClient(OpenAiChatModel model, ChatMemory chatMemory) {
        return ChatClient
                .builder(model)
                .defaultSystem(SystemConstants.GAME_SYSTEM_PROMPT)//提示词工程中的提示词我们的处理方法是将提示词使用常量进行储存，储存在其他的文件中；
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        new MessageChatMemoryAdvisor(chatMemory)
                )
                .build();
    }

    //使用相关tool结合mysql完成大模型综合调用
    @Bean
    public ChatClient serviceChatClient(OpenAiChatModel model, ChatMemory chatMemory, CourseTools courseTools) {
        return ChatClient
                .builder(model)
                .defaultSystem(SystemConstants.SERVICE_SYSTEM_PROMPT)
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        new MessageChatMemoryAdvisor(chatMemory)

                )
                .defaultTools(courseTools)
                .build();
    }

    //一个能够解析pdf文件的机器人
    @Bean
    public ChatClient pdfChatClient(OpenAiChatModel model, ChatMemory chatMemory,VectorStore vectorStore) {
        return ChatClient
                .builder(model)
                .defaultSystem("请你根据pdf中间的信息进行问题的解答，你的回答需要结合pdf中间相关的信息进行回答，在pdf文件的基础之上完成对于问题的分析和解答")
                .defaultAdvisors(
                        new SimpleLoggerAdvisor(),
                        new MessageChatMemoryAdvisor(chatMemory),
                        new QuestionAnswerAdvisor(
                                vectorStore,
                                SearchRequest.builder()
                                        //.similarityThreshold(0.6)
                                        .topK(2)
                                        .build()
                        )
                )
                .build();
    }

}
