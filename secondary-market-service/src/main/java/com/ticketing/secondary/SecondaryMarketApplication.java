package com.ticketing.secondary;

import com.ticketing.secondary.config.ClientProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication(scanBasePackages = {"com.ticketing.secondary", "com.ticketing.common"})
@EnableCaching
@EnableConfigurationProperties(ClientProperties.class)
public class SecondaryMarketApplication {
    public static void main(String[] args) {
        SpringApplication.run(SecondaryMarketApplication.class, args);
    }
}
