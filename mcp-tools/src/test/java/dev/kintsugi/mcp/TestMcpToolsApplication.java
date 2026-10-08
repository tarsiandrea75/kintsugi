package dev.kintsugi.mcp;

import org.springframework.boot.SpringApplication;

public class TestMcpToolsApplication {

	public static void main(String[] args) {
		SpringApplication.from(McpToolsApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
