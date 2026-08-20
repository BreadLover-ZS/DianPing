package com.dish.review;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAspectJAutoProxy(exposeProxy = true)
@EnableScheduling
@MapperScan("com.dish.review.mapper")
@SpringBootApplication
public class DishReviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(DishReviewApplication.class, args);
    }

}
