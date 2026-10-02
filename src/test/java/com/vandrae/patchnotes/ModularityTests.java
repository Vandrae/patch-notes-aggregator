package com.vandrae.patchnotes;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(PatchNotesApplication.class);

    /** Fails the build on cyclic dependencies, access to another module's internals, or undeclared dependencies. */
    @Test
    void moduleStructureIsValid() {
        modules.verify();
    }

    /** Writes C4/PlantUML diagrams of the real module graph to target/spring-modulith-docs. */
    @Test
    void writesModuleDiagrams() {
        new Documenter(modules).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
    }
}
