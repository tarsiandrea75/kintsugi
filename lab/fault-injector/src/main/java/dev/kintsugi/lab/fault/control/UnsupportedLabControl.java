package dev.kintsugi.lab.fault.control;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import dev.kintsugi.lab.fault.catalog.Fault;

/**
 * Placeholder {@link LabControl} for a context without Docker or gateway-mock (tests, a bare {@code java -jar}). Selected
 * by {@code kintsugi.lab-control.mode=unsupported}, the default; the compose deployment selects {@code docker}.
 *
 * <p>
 * {@link #activate} fails before applying anything, so every activation is recorded as {@code FAILED}.
 * {@link #restore} is a no-op because this implementation never changed the lab: the compensating restore succeeds and
 * no injection is left blocking.
 */
@Component
@ConditionalOnProperty(prefix = "kintsugi.lab-control", name = "mode", havingValue = "unsupported",
		matchIfMissing = true)
public class UnsupportedLabControl implements LabControl {

	@Override
	public void activate(Fault fault) {
		throw new LabControlException(
				"lab control is not available (kintsugi.lab-control.mode=unsupported); nothing was applied");
	}

	@Override
	public void restore(Fault fault) {
		// Nothing was ever applied, so there is nothing to undo.
	}

}
