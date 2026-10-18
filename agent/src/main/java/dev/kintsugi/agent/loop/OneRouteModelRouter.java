package dev.kintsugi.agent.loop;

/**
 * M2's router: one route, picked at start by {@code kintsugi.agent.route}, for {@code ACT} and {@code REPORT}. Per-task
 * routes are M4's.
 */
public class OneRouteModelRouter implements ModelRouter {

	private final ModelRoute route;

	public OneRouteModelRouter(ModelRoute route) {
		this.route = route;
	}

	@Override
	public ModelRoute route(ModelTask task) {
		return this.route;
	}

}
