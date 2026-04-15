package ru.copperside.controlledpersonsregistry;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.kafka.annotation.EnableKafka;
import ru.copperside.controlledpersonsregistry.config.ControlledPersonsRegistryProperties;

@SpringBootApplication
@EnableKafka
@EnableConfigurationProperties({ControlledPersonsRegistryProperties.class})
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}