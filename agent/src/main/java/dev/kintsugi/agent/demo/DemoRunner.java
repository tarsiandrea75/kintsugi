package dev.kintsugi.agent.demo;

import java.net.ConnectException;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Asks one question through the tool-enabled client. Runs only under the {@code demo} profile. */
@Component
@Profile("demo")
public class DemoRunner implements ApplicationRunner, ExitCodeGenerator {

	static final String QUESTION = "What time is it in Buenos Aires?";

	private final ChatClient chatClient;

	private final String baseUrl;

	private int exitCode;

	public DemoRunner(ChatClient chatClient,
			@Value("${spring.ai.ollama.base-url:http://localhost:11434}") String baseUrl) {
		this.chatClient = chatClient;
		this.baseUrl = baseUrl;
	}

	@Override
	public void run(ApplicationArguments args) {
		try {
			System.out.println(chatClient.prompt().user(QUESTION).call().content());
		}
		catch (RuntimeException e) {
			if (!isConnectionFailure(e)) {
				throw e;
			}
			System.err.println("Ollama not reachable at " + baseUrl + ". Start it with: ollama serve");
			exitCode = 1;
		}
	}

	@Override
	public int getExitCode() {
		return exitCode;
	}

	private static boolean isConnectionFailure(Throwable error) {
		for (Throwable t = error; t != null; t = t.getCause()) {
			if (t instanceof ConnectException) {
				return true;
			}
		}
		return false;
	}

}
