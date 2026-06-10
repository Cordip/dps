plugins {
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.jakarta.xml.bind)
    runtimeOnly(libs.jaxb.runtime)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "org.example.app.PeopleNormalizerMain"
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
}
