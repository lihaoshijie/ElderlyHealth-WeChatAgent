package com.Myself.demo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDate;

@Slf4j
@Service
public class CalendarService {

    private final WebClient webClient;
    private final String apiKey;
    private final ObjectMapper objectMapper;

    public CalendarService(@Value("${tianApi.key}") String apiKey) {
        this.apiKey = apiKey;
        this.objectMapper = new ObjectMapper();
        this.webClient = WebClient.builder()
                .baseUrl("https://apis.tianapi.com")
                .build();
        log.info("CalendarService 初始化完成");
    }

    public String getMonthlyCalendar(int year, int month) {
        try {
            String dateStr = String.format("%04d-%02d", year, month);
            String response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/jiejiari/index")
                            .queryParam("key", apiKey)
                            .queryParam("date", dateStr)
                            .queryParam("type", 2)
                            .queryParam("mode", 1)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.debug("月历API响应: {} -> {}", dateStr, response);
            return parseMonthlyResponse(response, year, month);
        } catch (Exception e) {
            log.error("月历查询失败: {}-{}", year, month, e);
            return "月历查询失败: " + e.getMessage();
        }
    }

    public String getDateDetail(String date) {
        try {
            String response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/jiejiari/index")
                            .queryParam("key", apiKey)
                            .queryParam("date", date)
                            .queryParam("type", 0)
                            .queryParam("mode", 1)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.debug("日期详情API响应: {} -> {}", date, response);
            return parseDateDetailResponse(response);
        } catch (Exception e) {
            log.error("日期详情查询失败: {}", date, e);
            return "日期查询失败: " + e.getMessage();
        }
    }

    public String getYearlyHolidays(int year) {
        try {
            String response = webClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/jiejiari/index")
                            .queryParam("key", apiKey)
                            .queryParam("date", String.valueOf(year))
                            .queryParam("type", 1)
                            .queryParam("mode", 1)
                            .build())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.debug("年度节假日API响应: {} -> {}", year, response);
            return parseYearlyResponse(response, year);
        } catch (Exception e) {
            log.error("年度节假日查询失败: {}", year, e);
            return "年度节假日查询失败: " + e.getMessage();
        }
    }

    private String parseMonthlyResponse(String response, int year, int month) {
        try {
            JsonNode root = objectMapper.readTree(response);
            int code = root.path("code").asInt();
            if (code != 200) {
                return "月历查询失败: " + root.path("msg").asText();
            }

            JsonNode list = root.path("result").path("list");
            if (!list.isArray() || list.isEmpty()) {
                return "暂无该月数据";
            }

            StringBuilder sb = new StringBuilder();
            sb.append(".-------- ").append(year).append("年").append(month).append("月 -------.\n");
            sb.append("| 一 二 三 四 五 六 日|\n");

            int[] order = {1, 2, 3, 4, 5, 6, 7};

            sb.append("|");
            int firstW = toWeekday(list.get(0).path("weekday").asInt());
            int firstPos = 0;
            while (firstPos < 7 && order[firstPos] != firstW) firstPos++;
            for (int i = 0; i < firstPos; i++) sb.append("   ");

            String currentHoliday = null;
            for (JsonNode day : list) {
                int dayNum = Integer.parseInt(day.path("date").asText().substring(8));
                int weekday = toWeekday(day.path("weekday").asInt());
                int daycode = day.path("daycode").asInt();
                String name = day.path("name").asText("");

                String m = " ";
                if (daycode == 1) m = "*";
                else if (daycode == 2) m = "-";
                else if (daycode == 3) m = "#";

                if (!name.isEmpty() && !name.equals(currentHoliday)) {
                    currentHoliday = name;
                }

                String cell = dayNum < 10 ? " " + dayNum + m : dayNum + m;
                sb.append(cell);

                if (weekday == 7) {
                    sb.append(" |\n");
                    if (dayNum < 31) sb.append("|");
                }
            }

            int lastW = toWeekday(list.get(list.size() - 1).path("weekday").asInt());
            int lastPos = 0;
            while (lastPos < 7 && order[lastPos] != lastW) lastPos++;
            for (int i = lastPos + 1; i < 7; i++) sb.append("   ");
            sb.append(" |");
            sb.append("\n`-------- ").append(year).append("年").append(month).append("月 --------'\n");

            sb.append("\n*节假日  -双休日  #调休  空格工作日\n");

            sb.append("\n农历:\n");
            for (JsonNode day : list) {
                String date = day.path("date").asText();
                int dayNum = Integer.parseInt(date.substring(8));
                String lunarMonth = day.path("lunarmonth").asText("");
                String lunarDay = day.path("lunarday").asText("");
                String lunarYear = day.path("lunaryear").asText("");
                String name = day.path("name").asText("");

                String lunarDayTrimmed = lunarDay.trim();
                if (dayNum == 1 || "初一".equals(lunarDayTrimmed) || !name.isEmpty()) {
                    sb.append("  ").append(date.substring(5));
                    if (!lunarYear.isEmpty()) sb.append(" ").append(lunarYear).append("年");
                    sb.append(" ").append(lunarMonth).append(lunarDayTrimmed);
                    if (!name.isEmpty()) sb.append(" ").append(name);
                    sb.append("\n");
                }
            }

            if (currentHoliday != null) {
                String tip = list.get(0).path("tip").asText("");
                if (!tip.isEmpty()) {
                    sb.append("\n").append(tip).append("\n");
                }
            }

            log.info("月历查询成功: {}-{}", year, month);
            return sb.toString();
        } catch (Exception e) {
            log.error("解析月历响应失败", e);
            return "月历查询失败: 数据解析异常";
        }
    }

    private String parseDateDetailResponse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            int code = root.path("code").asInt();
            if (code != 200) {
                return "日期查询失败: " + root.path("msg").asText();
            }

            JsonNode result = root.path("result");
            if (result.isArray() && result.size() > 0) {
                result = result.get(0);
            }

            String date = result.path("date").asText();
            String cnweekday = result.path("cnweekday").asText();
            String lunarYear = result.path("lunaryear").asText("");
            String lunarMonth = result.path("lunarmonth").asText("");
            String lunarDay = result.path("lunarday").asText("").trim();
            int daycode = result.path("daycode").asInt();
            int isnotwork = result.path("isnotwork").asInt();
            String name = result.path("name").asText("");
            String enname = result.path("enname").asText("");
            String info = result.path("info").asText("");
            String tip = result.path("tip").asText("");

            StringBuilder sb = new StringBuilder();
            sb.append(".-------------------------------.\n");
            sb.append("| ").append(date).append(" ").append(cnweekday);
            int pad = 31 - date.length() - cnweekday.length();
            for (int i = 0; i < pad; i++) sb.append(" ");
            sb.append(" |\n");
            sb.append("|-------------------------------|\n");

            String dayType;
            switch (daycode) {
                case 1: dayType = "法定节假日"; break;
                case 2: dayType = "双休日"; break;
                case 3: dayType = "调休上班日"; break;
                default: dayType = "工作日"; break;
            }
            sb.append("| 类型: ").append(dayType);
            if (isnotwork == 1) {
                sb.append(" 休息");
            } else {
                sb.append(" 上班");
            }
            for (int i = 0; i < 26 - dayType.length() - 3; i++) sb.append(" ");
            sb.append(" |\n");

            sb.append("| 农历: ").append(lunarYear).append("年 ").append(lunarMonth).append(lunarDay);
            int lpad = 28 - lunarYear.length() - lunarMonth.length() - lunarDay.length();
            for (int i = 0; i < lpad; i++) sb.append(" ");
            sb.append(" |\n");

            if (!name.isEmpty()) {
                sb.append("| 节日: ").append(name).append(" ");
                if (!enname.isEmpty()) {
                    sb.append("(").append(enname).append(") ");
                }
                int npad = 28 - name.length() - (enname.isEmpty() ? 0 : enname.length() + 3);
                for (int i = 0; i < npad; i++) sb.append(" ");
                sb.append(" |\n");
            }

            if (!tip.isEmpty()) {
                sb.append("| ").append(tip);
                int tpad = 31 - tip.length();
                for (int i = 0; i < tpad; i++) sb.append(" ");
                sb.append(" |\n");
            }

            String rest = result.path("rest").asText("");
            if (!rest.isEmpty()) {
                sb.append("| ").append(rest);
                int rpad = 31 - rest.length();
                for (int i = 0; i < rpad; i++) sb.append(" ");
                sb.append(" |\n");
            }

            sb.append("`-------------------------------'");
            return sb.toString();
        } catch (Exception e) {
            log.error("解析日期详情响应失败", e);
            return "日期查询失败: 数据解析异常";
        }
    }

    private String parseYearlyResponse(String response, int year) {
        try {
            JsonNode root = objectMapper.readTree(response);
            int code = root.path("code").asInt();
            if (code != 200) {
                return "年度节假日查询失败: " + root.path("msg").asText();
            }

            JsonNode list = root.path("result").path("list");
            if (!list.isArray() || list.isEmpty()) {
                return "暂无节假日数据";
            }

            StringBuilder sb = new StringBuilder();
            sb.append(".-------------- ").append(year).append("年节假日 ");
            for (int i = 0; i < 20 - String.valueOf(year).length(); i++) sb.append("-");
            sb.append(".\n");

            String lastHoliday = "";
            for (JsonNode day : list) {
                String name = day.path("name").asText("");
                String date = day.path("date").asText("");
                String cnweekday = day.path("cnweekday").asText("");
                int wage = day.path("wage").asInt();

                if (!name.isEmpty() && !name.equals(lastHoliday)) {
                    if (!lastHoliday.isEmpty()) {
                        sb.append("|\n");
                    }
                    sb.append("| ").append(name).append("\n");
                    lastHoliday = name;
                }

                if (!name.isEmpty()) {
                    sb.append("|   ").append(date).append(" ").append(cnweekday);
                    if (wage >= 3) {
                        sb.append(" 3x工资");
                    }
                    sb.append("\n");
                }
            }

            String tip = root.path("result").path("tip").asText("");
            if (!tip.isEmpty()) {
                sb.append("|\n| ").append(tip).append("\n");
            }

            for (int i = 0; i < 33; i++) sb.append("-");
            sb.append("'");

            log.info("年度节假日查询成功: {}", year);
            return sb.toString();
        } catch (Exception e) {
            log.error("解析年度节假日响应失败", e);
            return "年度节假日查询失败: 数据解析异常";
        }
    }

    private int toWeekday(int w) {
        return w == 0 ? 7 : w;
    }
}
