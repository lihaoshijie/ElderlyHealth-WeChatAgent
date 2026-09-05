package com.elderlyhealth.agent.tool;

import com.elderlyhealth.agent.service.SciCalcService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SciCalcTool {

    private final SciCalcService sciCalcService;

    public SciCalcTool(SciCalcService sciCalcService) {
        this.sciCalcService = sciCalcService;
    }

    @Tool(name = "sci_calc", value = "科学计算器")
    public String calculate(
            @P("运算类型") String type,
            @P("计算数值") String num) {
        log.info("科学计算: type={}, num={}", type, num);
        return sciCalcService.calculate(type, num);
    }
}
