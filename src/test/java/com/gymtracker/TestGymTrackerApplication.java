package com.gymtracker;

import org.springframework.boot.SpringApplication;

public class TestGymTrackerApplication {

    public static void main(String[] args) {
        SpringApplication.from(GymTrackerApplication::main).with(TestcontainersConfiguration.class).run(args);
    }
}
