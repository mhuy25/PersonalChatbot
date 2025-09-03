package com.example.personalchatbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PersonalChatbotApplication {

    public static void main(String[] args) {
        SpringApplication.run(PersonalChatbotApplication.class, args);
    }

}
