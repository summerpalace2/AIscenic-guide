package com.ai.guide.infrastructure.planner.amap;

import com.ai.guide.infrastructure.rag.amap.AmapWebServiceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 高德地图 Web 服务规划器客户端配置
 *
 * 架构定位：COLA 基础设施层 (Infrastructure Layer) - 第三方地图客户端配置。
 * 架构职责：读取高德 Web 服务 API Key、请求超时时间与最大并发阈值配置。
 */
@Configuration
public class AmapPlannerConfig {

    @Value("${amap.web-service.base-url:https://restapi.amap.com}")
    private String baseUrl;

    @Value("${amap.web-service.key:}")
    private String apiKey;

    @Value("${amap.web-service.timeout-ms:6000}")
    private int timeoutMs;

    @Bean
    public RestTemplate amapRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.max(500, timeoutMs));
        factory.setReadTimeout(Math.max(500, timeoutMs));
        return new RestTemplate(factory);
    }

    @Bean
    public AmapWebServiceClient amapWebServiceClient(RestTemplate amapRestTemplate) {
        return new AmapWebServiceClient(baseUrl, apiKey, amapRestTemplate);
    }
}
