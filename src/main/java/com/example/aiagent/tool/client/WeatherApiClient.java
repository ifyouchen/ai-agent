package com.example.aiagent.tool.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * OpenWeatherMap 天气 API 客户端
 * <p>
 * 免费版接口文档：https://openweathermap.org/current
 * 申请免费 API Key：https://home.openweathermap.org/users/sign_up
 * <p>
 * 配置项：weather.api.key（留空则所有请求返回 null，触发降级逻辑）
 */
@Slf4j
@Component
public class WeatherApiClient {

    private static final String API_URL =
            "https://api.openweathermap.org/data/2.5/weather?q={city}&appid={apiKey}&units=metric&lang=zh_cn";

    @Value("${weather.api.key:}")
    private String apiKey;

    private final RestTemplate restTemplate;

    public WeatherApiClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 调用 OpenWeatherMap API 获取实时天气。
     *
     * @param city 城市名（英文或中文拼音，如 Beijing / Shanghai）
     * @return 封装好的天气结果，apiKey 为空或请求失败时返回 null
     */
    public WeatherResult fetchWeather(String city) {
        if (!StringUtils.hasText(apiKey)) {
            log.debug("weather.api.key 未配置，跳过 API 调用");
            return null;
        }

        try {
            log.info("调用 OpenWeatherMap API，city={}", city);
            OWMResponse response = restTemplate.getForObject(
                    API_URL, OWMResponse.class, city, apiKey);

            if (response == null || response.getMain() == null) {
                log.warn("OpenWeatherMap 返回空响应，city={}", city);
                return null;
            }

            String desc = (response.getWeather() != null && !response.getWeather().isEmpty())
                    ? response.getWeather().get(0).getDescription()
                    : "未知";

            return WeatherResult.builder()
                    .description(desc)
                    .temperature(response.getMain().getTemp())
                    .humidity(response.getMain().getHumidity())
                    .windSpeed(response.getWind() != null ? response.getWind().getSpeed() : 0.0)
                    .build();

        } catch (Exception e) {
            log.warn("OpenWeatherMap API 调用失败，city={}，原因：{}", city, e.getMessage());
            return null;
        }
    }

    // ----------------------------------------------------------------
    // 内部 DTO：映射 OpenWeatherMap JSON 响应
    // ----------------------------------------------------------------

    /** 天气查询结果，供上层业务使用 */
    @Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class WeatherResult {
        private String description;   // 天气描述
        private double temperature;   // 温度 °C
        private int humidity;         // 湿度 %
        private double windSpeed;     // 风速 m/s
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class OWMResponse {
        private List<WeatherItem> weather;
        private MainData main;
        private WindData wind;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class WeatherItem {
        private String description;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class MainData {
        private double temp;
        private int humidity;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class WindData {
        private double speed;
    }
}
