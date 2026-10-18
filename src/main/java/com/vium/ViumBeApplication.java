package com.vium;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class ViumBeApplication {

	public static void main(String[] args) {
		SpringApplication.run(ViumBeApplication.class, args);
	}

}
