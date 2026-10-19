package com.serverping;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point. Placed in the root package so {@code @ComponentScan} covers every class below it
 * — a controller living outside this package would be silently invisible to Spring, which
 * surfaces as a 404 rather than a startup error.
 */
@SpringBootApplication
@EnableScheduling
public class ServerpingApplication {

	public static void main(String[] args) {
		SpringApplication.run(ServerpingApplication.class, args);
	}

}
