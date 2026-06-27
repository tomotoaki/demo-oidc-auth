package com.example.demoidcauthmobilebff;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

@SpringBootApplication
public class DemoOidcAuthMobileBffApplication {

    @PostConstruct
    public void initApplicationTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
    }

    public static void main(String[] args) {
        SpringApplication.run(DemoOidcAuthMobileBffApplication.class, args);
    }
}
