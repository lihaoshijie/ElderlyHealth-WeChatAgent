package com.elderlyhealth.agent.tool;

import com.elderlyhealth.agent.entity.WeatherResponse;
import com.elderlyhealth.agent.service.WeatherService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class WeatherTool {

    private final WeatherService weatherService;

    public WeatherTool(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @Tool(name = "weather", value = "查询全球城市实时/预报天气")
    public String queryWeather(
            @P("城市名称") String city,
            @P("查询天数") int days) {
        return doQuery(city, days);
    }

    private String doQuery(String city, int days) {
        try {
            if (days <= 1) {
                WeatherResponse w = weatherService.getWeather(city);
                return String.format(
                        "🌤 %s 实时天气\n\n天气: %s  温度: %s\n体感: %s  湿度: %s\n风向: %s  风速: %s\n风力: %s  能见度: %s\n气压: %s\n\n🕐 %s",
                        w.getCity(), w.getWeather(), w.getTemperature(),
                        w.getFeelsLike(), w.getHumidity(), w.getWindDirection(),
                        w.getWindSpeed(), w.getWindScale(), w.getVisibility(),
                        w.getPressure(), w.getObservationTime());
            } else {
                int apiDays = days <= 3 ? 3 : days <= 7 ? 7 : 15;
                return weatherService.getForecastMulti(city, apiDays, days);
            }
        } catch (Exception e) {
            return "天气查询失败: " + e.getMessage();
        }
    }
}
