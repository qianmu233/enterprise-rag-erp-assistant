package com.example.rag.chat;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.example.rag.config.ModelProperties;
import com.example.rag.config.ModelProperties.ModelItem;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

/**
 * 多模型注册中心。
 * <p>
 * 根据 {@link ModelProperties} 中配置的模型列表，为每个 provider 懒加载并缓存一个基础
 * {@link ChatClient}。调用方通过 {@link #getClient(String)} 传入 modelId 获取对应客户端，
 * 再通过 Spring AI 的 {@code ChatOptions.builder().model(modelName)} 在请求级覆盖模型名称。
 * <p>
 * 请求可切换已装配的模型；修改配置或新增 provider 需要重启，新增 provider 需：
 * <ol>
 *   <li>在 pom.xml 引入对应 spring-ai-starter-model-xxx 依赖</li>
 *   <li>在 application.yml 的 spring.ai.xxx 下配置 api-key</li>
 *   <li>在 app.models 列表中增加一条记录</li>
 * </ol>
 * provider bean 名称约定（Spring AI AutoConfiguration）：
 * <ul>
 *   <li>deepseek  → {@code deepSeekChatModel}</li>
 *   <li>openai    → {@code openAiChatModel}</li>
 *   <li>google-genai → {@code googleGenAiChatModel}</li>
 *   <li>其他 provider 仅保留 Spring AI 2.0.0 已确认适配的映射</li>
 * </ul>
 */
@Component
public class ModelRegistry {

    /**
     * provider 标识 → Spring AI AutoConfiguration 注册的 ChatModel bean 名称。
     * 仅保留 Spring AI 2.0.0 正式版已确认适配的 provider 映射，未注册 bean 时不会路由生效。
     */
    private static final Map<String, String> PROVIDER_BEAN_NAMES = Map.ofEntries(
            Map.entry("deepseek",          "deepSeekChatModel"),          // spring-ai-starter-model-deepseek
            Map.entry("openai",            "openAiChatModel"),            // spring-ai-starter-model-openai（通义千问等兼容）
            Map.entry("google-genai",      "googleGenAiChatModel"),       // spring-ai-starter-model-google-genai
            Map.entry("anthropic",         "anthropicChatModel"),         // spring-ai-starter-model-anthropic
            Map.entry("ollama",            "ollamaChatModel"),            // spring-ai-starter-model-ollama
            Map.entry("mistral",           "mistralAiChatModel"),         // spring-ai-starter-model-mistral-ai
            Map.entry("bedrock",           "bedrockProxyChatModel"),      // spring-ai-starter-model-bedrock-converse
            Map.entry("gateway",           "gatewayChatModel")           // 独立 gateway Profile，不复用 OpenAI 连接
    );

    private final ModelProperties modelProperties;
    private final ApplicationContext applicationContext;
    private final ChatClient.Builder defaultBuilder;

    /** provider → ChatClient 缓存（每个 provider 只创建一次基础客户端） */
    private final Map<String, ChatClient> providerClients = new ConcurrentHashMap<>();

    public ModelRegistry(ModelProperties modelProperties,
                         ApplicationContext applicationContext,
                         ChatClient.Builder defaultBuilder) {
        this.modelProperties = modelProperties;
        this.applicationContext = applicationContext;
        this.defaultBuilder = defaultBuilder;
    }

    /**
     * 根据 modelId 返回对应 provider 的基础 {@link ChatClient}。
     * 调用方需自行通过 {@code .options(ChatOptions.builder().model(modelName))}
     * 在每次请求时覆盖具体的模型名称。
     *
     * @param modelId 前端传入的模型 ID，null 或空时使用默认模型
     * @return 对应 provider 的 ChatClient
     */
    public ChatClient getClient(String modelId) {
        ModelItem item = getModelItem(modelId);
        if (item == null) return buildClientFromDefaultBuilder();
        return providerClients.computeIfAbsent(item.getProvider(), this::buildClientForProvider);
    }

    /**
     * 根据 modelId 获取对应的 ModelItem（用于取 modelName 传给 ChatOptions）。
     *
     * @param modelId 前端传入的模型 ID
     * @return 对应配置项；仅空 ID 使用默认，未知非空 ID 明确拒绝
     */
    public ModelItem getModelItem(String modelId) {
        ModelItem item;
        if (modelId == null || modelId.isBlank()) {
            item = modelProperties.getDefault();
        }
        else {
            item = modelProperties.getModels().stream()
                    .filter(candidate -> modelId.equals(candidate.getId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("未知模型 ID"));
        }
        // Gateway 必须显式选择，且配置或 Bean 缺失时不能走默认 Builder。
        if (item != null && ("gateway".equals(item.getProvider()) || "gateway-chat".equals(item.getId()))) {
            if (!"gateway-chat".equals(modelId) || !"gateway-chat".equals(item.getId())
                    || !"gateway".equals(item.getProvider())) {
                throw new IllegalStateException("Gateway 必须通过 gateway-chat 显式选择");
            }
            if (!applicationContext.containsBean("gatewayChatModel")) {
                throw new IllegalStateException("Gateway 模型未启用");
            }
        }
        return item;
    }

    /**
     * 获取默认模型的 modelName（用于日志、计费记录）。
     */
    public String getDefaultModelName() {
        ModelItem def = modelProperties.getDefault();
        return def != null ? def.getModelName() : "unknown";
    }

    /**
     * 根据 modelId 获取对应 provider 的原始 {@link ChatModel} bean。
     * 调用方用此 ChatModel 自行构建带 system prompt / tools 的 ChatClient。
     *
     * @param modelId 前端传入的模型 ID
     * @return 对应的 ChatModel bean，找不到时返回 null
     */
    public ChatModel getChatModel(String modelId) {
        ModelItem item = getModelItem(modelId);
        if (item == null) return null;
        String beanName = PROVIDER_BEAN_NAMES.get(item.getProvider());
        if (beanName != null && applicationContext.containsBean(beanName)) {
            return (ChatModel) applicationContext.getBean(beanName);
        }
        return null;
    }

    // ---------------------------------------------------------------

    private ChatClient buildClientForProvider(String provider) {
        String beanName = PROVIDER_BEAN_NAMES.get(provider);
        if (beanName != null && applicationContext.containsBean(beanName)) {
            ChatModel chatModel = (ChatModel) applicationContext.getBean(beanName);
            return ChatClient.builder(chatModel).build();
        }
        return buildClientFromDefaultBuilder();
    }

    private ChatClient buildClientFromDefaultBuilder() {
        return defaultBuilder.build();
    }

}
