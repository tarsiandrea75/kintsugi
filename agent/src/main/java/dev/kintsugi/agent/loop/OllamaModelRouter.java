package dev.kintsugi.agent.loop;

import org.springframework.ai.chat.model.ChatModel;

/** M2's router: one Ollama route, {@code kintsugi.agent.model}, for {@code ACT} and {@code REPORT}. */
public class OllamaModelRouter implements ModelRouter {

	private final ModelRoute route;

	public OllamaModelRouter(ChatModel chatModel, ModelRouteProperties properties) {
		this.route = new ModelRoute(properties.name(), chatModel, properties.temperature(), properties.numCtx(),
				properties.callTimeout());
	}

	@Override
	public ModelRoute route(ModelTask task) {
		return this.route;
	}

}
