package com.vandrae.patchnotes;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Modular monolith: each direct sub-package is an application module (see package-info.java in each),
 * and the allowed dependencies between them are verified by {@code ModularityTests}.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@EnableAsync // event listeners (@ApplicationModuleListener) run off the publisher's thread
public class PatchNotesApplication {

    public static void main(String[] args) {
        SpringApplication.run(PatchNotesApplication.class, args);
    }
}
