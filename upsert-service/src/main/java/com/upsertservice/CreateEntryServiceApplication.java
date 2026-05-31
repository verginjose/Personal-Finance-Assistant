package com.upsertservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class CreateEntryServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(CreateEntryServiceApplication.class, args);
    }
}
