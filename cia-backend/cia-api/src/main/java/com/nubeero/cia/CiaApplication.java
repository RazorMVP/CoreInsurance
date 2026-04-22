package com.nubeero.cia;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.nubeero.cia")
public class CiaApplication {

    public static void main(String[] args) {
        SpringApplication.run(CiaApplication.class, args);
    }
}
