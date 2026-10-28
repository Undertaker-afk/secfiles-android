import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    // Serpent, Twofish, Argon2id. AES comes from the JCE so Android uses its accelerated provider.
    api("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // Compression engines. LZ4 and Snappy are implemented in-tree; these two are pure Java (no Unsafe/JNI).
    implementation("org.tukaani:xz:1.10")
    implementation("org.itadaki:bzip2:0.9.1")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "1500m"
}
