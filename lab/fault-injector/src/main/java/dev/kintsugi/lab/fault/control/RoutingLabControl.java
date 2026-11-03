package dev.kintsugi.lab.fault.control;

import java.util.EnumMap;
import java.util.Map;

import dev.kintsugi.lab.fault.catalog.Fault;
import dev.kintsugi.lab.fault.catalog.LabMechanism;

/**
 * The {@link LabControl} of {@code kintsugi.lab-control.mode=docker}: sends each fault to the control of its catalog
 * {@link LabMechanism} ({@link HttpGatewayControl} for F1 and F2, {@link ComposeRecreateControl} for F3 and F4).
 */
public class RoutingLabControl implements LabControl {

	private final Map<LabMechanism, LabControl> controls;

	/** @throws IllegalArgumentException a mechanism has no control, so a catalog fault could not be applied */
	public RoutingLabControl(Map<LabMechanism, LabControl> controls) {
		for (LabMechanism mechanism : LabMechanism.values()) {
			if (!controls.containsKey(mechanism)) {
				throw new IllegalArgumentException("no lab control for mechanism " + mechanism);
			}
		}
		this.controls = new EnumMap<>(controls);
	}

	@Override
	public void activate(Fault fault, Map<String, Object> parameters) {
		controls.get(fault.mechanism()).activate(fault, parameters);
	}

	@Override
	public void restore(Fault fault) {
		controls.get(fault.mechanism()).restore(fault);
	}

}
