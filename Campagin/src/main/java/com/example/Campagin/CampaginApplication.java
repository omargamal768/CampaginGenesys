package com.example.Campagin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class CampaginApplication {

	public static void main(String[] args) {
		SpringApplication.run(CampaginApplication.class, args);
	}



}
