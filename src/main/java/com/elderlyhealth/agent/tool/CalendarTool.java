package com.elderlyhealth.agent.tool;

import com.elderlyhealth.agent.service.CalendarService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Slf4j
@Component
public class CalendarTool {

    private final CalendarService calendarService;

    public CalendarTool(CalendarService calendarService) {
        this.calendarService = calendarService;
    }

    @Tool(name = "calendar_month", value = "查看月历(含农历/节假日/调休)")
    public String getMonthlyCalendar(
            @P("年份(默认当前)") String year,
            @P("月份(默认当前)") String month) {
        int y = (year == null || year.isEmpty()) ? LocalDate.now().getYear() : Integer.parseInt(year);
        int m = (month == null || month.isEmpty()) ? LocalDate.now().getMonthValue() : Integer.parseInt(month);
        log.info("查询月历: {}-{}", y, m);
        return calendarService.getMonthlyCalendar(y, m);
    }

    @Tool(name = "calendar_date", value = "查询日期详情(农历/节假日/调休)")
    public String getDateDetail(
            @P("日期(yyyy-MM-dd)") String date) {
        if (date == null || date.isEmpty()) {
            date = LocalDate.now().toString();
        }
        log.info("查询日期详情: {}", date);
        return calendarService.getDateDetail(date);
    }

    @Tool(name = "calendar_year", value = "查询全年节假日安排")
    public String getYearlyHolidays(
            @P("年份(默认当前)") String year) {
        int y = (year == null || year.isEmpty()) ? LocalDate.now().getYear() : Integer.parseInt(year);
        log.info("查询年度节假日: {}", y);
        return calendarService.getYearlyHolidays(y);
    }
}
