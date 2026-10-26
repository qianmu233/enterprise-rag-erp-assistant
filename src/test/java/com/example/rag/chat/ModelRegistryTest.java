package com.example.rag.chat;

import java.util.List;
import java.util.Map;

import com.example.rag.config.ModelProperties;
import com.example.rag.config.ModelProperties.ModelItem;
import org.junit.jupiter.api.Test;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 多模型注册中心测试。
 */
class ModelRegistryTest {

	/**
	 * 验证当前启用的 provider 仍能路由到对应 ChatModel。
	 */
	@Test
	public void shouldRouteEnabledProviders() {
		ModelProperties properties = new ModelProperties();
		properties.setModels(List.of(
			model("deepseek-chat", "deepseek", "deepseek-chat", true),
			model("qwen-max", "openai", "qvq-max-2025-03-25", false),
			model("gemini-2.0-flash", "google-genai", "gemini-2.0-flash", false)));
		ApplicationContext context = mock(ApplicationContext.class);
		ChatModel deepSeekModel = mock(ChatModel.class);
		ChatModel openAiModel = mock(ChatModel.class);
		ChatModel googleGenAiModel = mock(ChatModel.class);
		when(context.containsBean("deepSeekChatModel")).thenReturn(true);
		when(context.containsBean("openAiChatModel")).thenReturn(true);
		when(context.containsBean("googleGenAiChatModel")).thenReturn(true);
		when(context.getBean("deepSeekChatModel")).thenReturn(deepSeekModel);
		when(context.getBean("openAiChatModel")).thenReturn(openAiModel);
		when(context.getBean("googleGenAiChatModel")).thenReturn(googleGenAiModel);
		ModelRegistry registry = new ModelRegistry(properties, context, mock(ChatClient.Builder.class));

		assertThat(registry.getChatModel("deepseek-chat")).isSameAs(deepSeekModel);
		assertThat(registry.getChatModel("qwen-max")).isSameAs(openAiModel);
		assertThat(registry.getChatModel("gemini-2.0-flash")).isSameAs(googleGenAiModel);
	}

	/**
	 * 验证 Spring AI 2.0.0 已确认适配的 provider 映射仍能路由。
	 */
	@Test
	public void shouldRouteSpringAi2CompatibleProviders() {
		Map<String, String> providerBeanNames = Map.ofEntries(
			Map.entry("anthropic", "anthropicChatModel"),
			Map.entry("ollama", "ollamaChatModel"),
			Map.entry("mistral", "mistralAiChatModel"),
			Map.entry("bedrock", "bedrockProxyChatModel"));
		ModelProperties properties = new ModelProperties();
		properties.setModels(providerBeanNames.keySet().stream()
			.map(provider -> model(provider + "-test", provider, provider + "-model", false))
			.toList());
		ApplicationContext context = mock(ApplicationContext.class);
		Map<String, ChatModel> chatModels = providerBeanNames.keySet().stream()
			.collect(java.util.stream.Collectors.toMap(provider -> provider, provider -> mock(ChatModel.class)));
		providerBeanNames.forEach((provider, beanName) -> {
			when(context.containsBean(beanName)).thenReturn(true);
			when(context.getBean(beanName)).thenReturn(chatModels.get(provider));
		});
		ModelRegistry registry = new ModelRegistry(properties, context, mock(ChatClient.Builder.class));

		providerBeanNames.keySet()
			.forEach(provider -> assertThat(registry.getChatModel(provider + "-test")).isSameAs(chatModels.get(provider)));
	}

	/**
	 * 验证 Spring AI 2.0.0 正式版未确认适配的历史 provider 不会被误路由。
	 */
	@Test
	public void shouldNotRouteUnconfirmedHistoricalProviders() {
		ModelProperties properties = new ModelProperties();
		properties.setModels(List.of(
			model("azure-openai-test", "azure-openai", "azure-model", false),
			model("minimax-test", "minimax", "minimax-model", false),
			model("zhipu-test", "zhipu", "zhipu-model", false)));
		ApplicationContext context = mock(ApplicationContext.class);
		when(context.containsBean("azureOpenAiChatModel")).thenReturn(true);
		when(context.containsBean("miniMaxChatModel")).thenReturn(true);
		when(context.containsBean("zhiPuAiChatModel")).thenReturn(true);
		ModelRegistry registry = new ModelRegistry(properties, context, mock(ChatClient.Builder.class));

		assertThat(registry.getChatModel("azure-openai-test")).isNull();
		assertThat(registry.getChatModel("minimax-test")).isNull();
		assertThat(registry.getChatModel("zhipu-test")).isNull();
	}

	@Test
	void shouldRejectUnknownIdsAcrossEveryRegistryEntryPoint() {
		ModelProperties properties = new ModelProperties();
		properties.setModels(List.of(model("deepseek-chat", "deepseek", "deepseek-chat", true)));
		ApplicationContext context = mock(ApplicationContext.class);
		ChatClient.Builder builder = mock(ChatClient.Builder.class);
		ModelRegistry registry = new ModelRegistry(properties, context, builder);
		for (String id : List.of("unknown", "gateway-chat", "gateway-chta")) {
			assertThatThrownBy(() -> registry.getModelItem(id)).isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> registry.getChatModel(id)).isInstanceOf(IllegalArgumentException.class);
			assertThatThrownBy(() -> registry.getClient(id)).isInstanceOf(IllegalArgumentException.class);
		}
		verifyNoInteractions(context, builder);
	}

	@Test
	void shouldKeepBlankIdsOnTheOriginalDefault() {
		ModelProperties properties = new ModelProperties();
		ModelItem original = model("deepseek-chat", "deepseek", "deepseek-chat", true);
		properties.setModels(List.of(original));
		ModelRegistry registry = new ModelRegistry(properties, mock(ApplicationContext.class), mock(ChatClient.Builder.class));
		assertThat(registry.getModelItem(null)).isSameAs(original);
		assertThat(registry.getModelItem("")).isSameAs(original);
		assertThat(registry.getModelItem(" ")).isSameAs(original);
	}

	@Test
	void shouldFailClosedWhenGatewayBeanIsMissing() {
		ModelProperties properties = new ModelProperties();
		properties.setModels(List.of(
			model("deepseek-chat", "deepseek", "deepseek-chat", true),
			model("gateway-chat", "gateway", "gateway-chat", false)));
		ChatClient.Builder builder = mock(ChatClient.Builder.class);
		ModelRegistry registry = new ModelRegistry(properties, mock(ApplicationContext.class), builder);
		assertThatThrownBy(() -> registry.getModelItem("gateway-chat"))
			.isInstanceOf(IllegalStateException.class).hasMessage("Gateway 模型未启用");
		assertThatThrownBy(() -> registry.getChatModel("gateway-chat")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> registry.getClient("gateway-chat")).isInstanceOf(IllegalStateException.class);
		verifyNoInteractions(builder);
	}

	@Test
	void shouldRejectGatewayAsDefaultOrAsAnAliasOfAnotherProvider() {
		ModelProperties properties = new ModelProperties();
		ModelRegistry registry = new ModelRegistry(properties, mock(ApplicationContext.class), mock(ChatClient.Builder.class));
		properties.setModels(List.of(model("gateway-chat", "gateway", "gateway-chat", true)));
		assertThatThrownBy(() -> registry.getModelItem(null)).isInstanceOf(IllegalStateException.class);
		properties.setModels(List.of(model("gateway-chat", "deepseek", "deepseek-chat", false)));
		assertThatThrownBy(() -> registry.getModelItem("gateway-chat")).isInstanceOf(IllegalStateException.class);
	}

	/** 构造测试用模型配置项。 */
	private static ModelItem model(String id, String provider, String modelName, boolean isDefault) {
		ModelItem item = new ModelItem();
		item.setId(id);
		item.setProvider(provider);
		item.setModelName(modelName);
		item.setDefault(isDefault);
		return item;
	}

}
