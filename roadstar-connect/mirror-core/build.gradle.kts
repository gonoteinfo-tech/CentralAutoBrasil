plugins {
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

sourceSets {
    test {
        java.srcDir("../tools")
        java.include("MirrorChecks.java")
    }
}

val protocolChecks by tasks.registering(JavaExec::class) {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("MirrorChecks")
}

tasks.check {
    dependsOn(protocolChecks)
}
