package com.example.demoidcauth;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.TimeZone;

@SpringBootApplication
public class DemoOidcAuthApplication {

    @PostConstruct
    public void initApplicationTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
    }

    public static void main(String[] args) {
        SpringApplication.run(DemoOidcAuthApplication.class, args);
    }
}
