package com.ai.guide.domain.planner.config;

import com.ai.guide.domain.rag.pipeline.AmapWebServiceClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 高德地图 Web 服务规划器客户端配置
 *
 * 所属领域：domain.planner.config（规划引擎配置）
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
