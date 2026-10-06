import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The digital twin: the real CHAMULLO core (Node, envelopes, receipts, Islands.decide) on simulated Wi-Fi Direct islands.
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

application {
    mainClass.set("ar.chamullo.sim.MainKt")
}

dependencies {
    implementation(project(":core"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}

// The lab, live: the twin serves the lab page at http://localhost:8787
tasks.register<JavaExec>("live") {
    group = "application"
    description = "Runs the CHAMULLO lab live on the digital twin"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("ar.chamullo.sim.LiveKt")
    args("8787")
}
