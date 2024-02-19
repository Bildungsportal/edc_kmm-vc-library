import at.asitplus.gradle.*
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")

    id("at.asitplus.gradle.vclib-conventions")
    id("org.jetbrains.dokka")
    id("signing")
}

/* required for maven publication */
val artifactVersion: String by extra
group = "at.asitplus.wallet"
version = artifactVersion



kotlin {
    ios()
    iosSimulatorArm64()
    jvm()
    sourceSets {
       val commonMain by getting {
            dependencies {
                commonImplementationDependencies()
                api("at.asitplus.crypto:datatypes-cose:${VcLibVersions.kmpcrypto}")
                api("at.asitplus.crypto:datatypes-jws:${VcLibVersions.kmpcrypto}")
                api(datetime())
                api(serialization("cbor"))
                api(kmmresult())
                api("io.matthewnelson.kotlin-components:encoding-base16:${VcLibVersions.encoding}")
                api("io.matthewnelson.kotlin-components:encoding-base64:${VcLibVersions.encoding}")
            }
        }


        val commonTest by getting

        val iosMain by getting
        val iosSimulatorArm64Main by getting { dependsOn(iosMain) }
        val iosTest by getting

        val jvmMain by getting {
            dependencies {
                implementation(bouncycastle("bcpkix"))
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation("com.nimbusds:nimbus-jose-jwt:${VcLibVersions.Jvm.`jose-jwt`}")
                implementation("org.json:json:${VcLibVersions.Jvm.json}")
            }
        }
    }
}

exportIosFramework("VcLibKmm", *commonIosExports())

val javadocJar = setupDokka(
    baseUrl = "https://github.com/a-sit-plus/kmm-vc-library/tree/main/",
    multiModuleDoc = true
)

publishing {
    publications {
        withType<MavenPublication> {
            artifact(javadocJar)
            pom {
                name.set("KmmVcLib")
                description.set("Kotlin Multiplatform library implementing the W3C VC Data Model")
                url.set("https://github.com/a-sit-plus/kmm-vc-library")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("JesusMcCloud")
                        name.set("Bernd Prünster")
                        email.set("bernd.pruenster@a-sit.at")
                    }
                    developer {
                        id.set("nodh")
                        name.set("Christian Kollmann")
                        email.set("christian.kollmann@a-sit.at")
                    }
                }
                scm {
                    connection.set("scm:git:git@github.com:a-sit-plus/kmm-vc-library.git")
                    developerConnection.set("scm:git:git@github.com:a-sit-plus/kmm-vc-library.git")
                    url.set("https://github.com/a-sit-plus/kmm-vc-library")
                }
            }
        }
    }
    repositories {
        mavenLocal {
            signing.isRequired = false
        }
        maven {
            url = uri(layout.projectDirectory.dir("..").dir("repo"))
            name = "local"
            signing.isRequired = false
        }
    }
}

signing {
    val signingKeyId: String? by project
    val signingKey: String? by project
    val signingPassword: String? by project
    useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
    sign(publishing.publications)
}