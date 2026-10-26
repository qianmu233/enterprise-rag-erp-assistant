package com.example.rag.config;

import java.net.URI;
import java.time.Duration;

import com.example.rag.chat.GatewayChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.Assert;

/** Gateway 使用独立连接参数；不改变默认模型及原有 OpenAI provider。 */
@Configuration(proxyBeanMethods = false)
@Profile("gateway")
@EnableConfigurationProperties(GatewayModelConfig.Properties.class)
public class GatewayModelConfig {

    @Bean
    public GatewayChatModel gatewayChatModel(Properties properties, ToolCallingManager toolCallingManager) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(properties.baseUrl())
                .apiKey(properties.apiKey())
                .model(properties.model())
                .timeout(properties.timeout())
                .maxRetries(properties.maxRetries())
                .streamUsage(true)
                .build();
        return new GatewayChatModel(OpenAiChatModel.builder()
                .options(options)
                .toolCallingManager(toolCallingManager)
                .build());
    }

    @ConfigurationProperties("app.gateway")
    public record Properties(String baseUrl, String apiKey, String model, Duration timeout, int maxRetries) {
        public Properties {
            Assert.hasText(baseUrl, "Gateway base-url 不能为空");
            URI uri = URI.create(baseUrl);
            Assert.isTrue(("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null
                    && "/v1".equals(uri.getPath()), "Gateway base-url 必须是以 /v1 结尾的 HTTP(S) 地址");
            Assert.hasText(apiKey, "Gateway api-key 不能为空");
            Assert.isTrue("gateway-chat".equals(model), "Gateway model 必须为 gateway-chat");
            Assert.isTrue(timeout != null && !timeout.isZero() && !timeout.isNegative(),
                    "Gateway timeout 必须大于 0");
            Assert.isTrue(maxRetries == 0, "本地 Gateway 接入不启用客户端自动重试");
        }
    }
}
