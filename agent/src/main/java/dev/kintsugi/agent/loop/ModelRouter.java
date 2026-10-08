package dev.kintsugi.agent.loop;

/**
 * Picks the chat model and its options for each task (ADR-010, "The model router"). In M2 there is one route, the
 * local Ollama model, for every task; the routes of the M4 configurations are a change here, not in the loop.
 */
public interface ModelRouter {

	ModelRoute route(ModelTask task);

}
