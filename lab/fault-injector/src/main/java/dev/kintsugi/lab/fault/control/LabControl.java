package dev.kintsugi.lab.fault.control;

import dev.kintsugi.lab.fault.catalog.Fault;

/**
 * The port through which the injector breaks and repairs the lab. Both methods either complete their steps or throw
 * {@link LabControlException}; the caller records the outcome. {@code kintsugi.lab-control.mode} selects the bean:
 * {@code docker} gives {@link RoutingLabControl} (gateway-mock admin API for F1 and F2, a bounded compose recreate for F3
 * and F4), {@code unsupported} gives {@link UnsupportedLabControl}. Tests use an in-memory fake.
 */
public interface LabControl {

	/** Applies the activation steps of {@code fault}. */
	void activate(Fault fault);

	/** Applies the restore steps of {@code fault}. Must be safe to repeat. */
	void restore(Fault fault);

}
