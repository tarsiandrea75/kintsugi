package dev.kintsugi.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.context.WebApplicationContext;

@SpringBootApplication
public class AgentApplication {

	/**
	 * Serves the incident API until stopped. The {@code demo} profile runs no web server: it asks its question and
	 * exits with {@code DemoRunner}'s exit code, as before the API existed.
	 */
	public static void main(String[] args) {
		ConfigurableApplicationContext context = SpringApplication.run(AgentApplication.class, args);
		if (!(context instanceof WebApplicationContext)) {
			System.exit(SpringApplication.exit(context));
		}
	}

}
