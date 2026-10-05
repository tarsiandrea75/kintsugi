package dev.kintsugi.agent.smoke;

import java.time.Duration;

/**
 * One tool invocation as the model proposed it.
 *
 * @param toolName the tool the model called
 * @param arguments the raw arguments JSON the model produced
 * @param error whether the tool rejected the arguments (result starts with {@code Error:}) or threw
 * @param latency how long the tool itself took
 */
record ToolCall(String toolName, String arguments, boolean error, Duration latency) {
}
