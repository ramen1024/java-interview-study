package com.jis;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.jis.**.mapper")
public class JisApplication {

    public static void main(String[] args) {
        SpringApplication.run(JisApplication.class, args);
    }
}
