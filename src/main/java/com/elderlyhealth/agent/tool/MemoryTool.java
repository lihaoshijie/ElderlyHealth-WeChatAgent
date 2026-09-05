package com.elderlyhealth.agent.tool;

import com.elderlyhealth.agent.mapper.AdminMapper;
import com.elderlyhealth.agent.service.MemoryService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class MemoryTool {

    private final MemoryService memoryService;

    @Autowired(required = false)
    private AdminMapper adminMapper;

    public MemoryTool(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Tool(name = "remember_fact", value = "记住用户个人信息")
    public String rememberFact(
            @P("信息类别，如：名字、生日、喜好、职业") String key,
            @P("具体信息") String value,
            @P("用户ID，系统自动填充") String userId) {
        if (key == null || key.isEmpty() || value == null || value.isEmpty()) {
            return "抱歉，我没记住";
        }
        memoryService.setFact(userId, key, value);
        // 同步昵称到 MySQL
        if (adminMapper != null && "名字".equals(key)) {
            try { adminMapper.updateNickname(userId, value); } catch (Exception ignored) {}
        }
        return "好的，已记住你的" + key + "是" + value;
    }
}
