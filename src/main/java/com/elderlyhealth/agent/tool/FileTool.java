package com.elderlyhealth.agent.tool;

import com.elderlyhealth.agent.bot.ToolExecutionContext;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

@Component
public class FileTool {

    @Tool(name = "translate_file", value = "翻译已上传的文件")
    public String translateFile(
            @P("目标语言") String targetLanguage,
            @P("翻译要求") String instruction) {
        ToolExecutionContext.recordFileTranslate(targetLanguage, instruction);
        return "正在翻译文件...";
    }

    @Tool(name = "extract_from_file", value = "从已上传文件提取指定内容")
    public String extractFromFile(
            @P("提取内容描述") String keyword,
            @P("输出格式") String format) {
        ToolExecutionContext.recordFileExtract(keyword, format);
        return "正在提取文件内容...";
    }

    @Tool(name = "search_in_file", value = "在已上传文件中搜索内容")
    public String searchInFile(
            @P("搜索关键词") String query,
            @P("上下文行数") int contextLines) {
        ToolExecutionContext.recordFileSearch(query, contextLines);
        return "正在搜索文件...";
    }

    @Tool(name = "export_file_summary", value = "导出文件总结/摘要")
    public String exportFileSummary() {
        ToolExecutionContext.recordFileExport();
        return "正在生成文件总结...";
    }

    @Tool(name = "save_as_file", value = "将回答内容保存为文件")
    public String saveAsFile(
            @P("文件名") String fileName) {
        ToolExecutionContext.recordSaveAsFile(null, fileName);
        return "";
    }
}
