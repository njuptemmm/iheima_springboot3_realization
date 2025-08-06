//在springboot能够实现使用getText（）来获取前面的交流记录
//在这里我们需要在前面的数据中获取对话的role以及对应的content就能够在前端实现对应的效果

package com.example.demo.entity.vo;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;

@NoArgsConstructor
@Data
public class MessageVO {
    private String role;
    private String content;

    public MessageVO(Message message) {
        switch (message.getMessageType()) {
            case USER :
                this.role = "user";
                break;
            case ASSISTANT :
                this.role = "assistant";
                break;
            default :
                role="";
                break;
        }
        this.content = message.getText();
    }
}
