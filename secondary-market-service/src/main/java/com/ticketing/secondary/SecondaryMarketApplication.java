package com.ticketing.secondary;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class SecondaryMarketApplication {
    public static void main(String[] args) {
        SpringApplication.run(SecondaryMarketApplication.class, args);
    }
}
