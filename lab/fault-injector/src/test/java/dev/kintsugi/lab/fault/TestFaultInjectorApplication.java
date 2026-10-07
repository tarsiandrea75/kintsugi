package dev.kintsugi.lab.fault;

import org.springframework.boot.SpringApplication;

public class TestFaultInjectorApplication {

	public static void main(String[] args) {
		SpringApplication.from(FaultInjectorApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
