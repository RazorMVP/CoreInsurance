package com.nubeero.cia;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication(scanBasePackages = "com.nubeero.cia")
public class CiaApplication {

    public static void main(String[] args) {
        SpringApplication.run(CiaApplication.class, args);
    }
}
