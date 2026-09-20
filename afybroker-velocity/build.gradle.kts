plugins {
    alias(libs.plugins.shadow)
}

dependencies {
    compileOnly(libs.velocity.api)
    implementation(libs.bstats.velocity)
    implementation(project(":afybroker-client"))
    testImplementation(libs.velocity.api)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockito.core)
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.shadowJar {
    relocate("org.bstats", "net.afyer.afybroker.velocity.bstats")
}


tasks.processResources {
    val props = mapOf(
        "version" to project.version
    )
    inputs.properties(props)
    filesMatching("velocity-plugin.json") {
        expand(props)
    }
}
