package com.bairimeng.keeplearning;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
        "com.bairimeng.keeplearning"
})
public class KeepLearningApplication {

    public static void main(String[] args) {
        SpringApplication.run(KeepLearningApplication.class, args);
    }

}
