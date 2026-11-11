import org.gradle.api.tasks.testing.Test
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.cloudtoolkit"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenLocal()
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local("D:/Program/JetBrains/idea-2026.2.4.win")
        testFramework(TestFrameworkType.Platform, "262.11879.100")
        testBundledPlugins("com.intellij.modules.ultimate")
    }

    implementation("org.apache.sshd:sshd-core:2.19.0") {
        exclude(group = "org.slf4j", module = "slf4j-simple")
        exclude(group = "org.slf4j", module = "slf4j-nop")
        exclude(group = "ch.qos.logback", module = "logback-classic")
    }
    implementation("org.apache.sshd:sshd-sftp:2.19.0") {
        exclude(group = "org.slf4j", module = "slf4j-simple")
        exclude(group = "org.slf4j", module = "slf4j-nop")
        exclude(group = "ch.qos.logback", module = "logback-classic")
    }

    implementation("org.xerial:sqlite-jdbc:3.49.1.0")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
        }
    }
    pluginVerification {
        ides {
            current()
        }
    }
}

tasks.withType<JavaCompile> {
    options.release.set(25)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
