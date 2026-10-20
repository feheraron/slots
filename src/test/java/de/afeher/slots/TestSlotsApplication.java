package de.afeher.slots;

import org.springframework.boot.SpringApplication;

public class TestSlotsApplication {

	public static void main(String[] args) {
		SpringApplication.from(SlotsApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
