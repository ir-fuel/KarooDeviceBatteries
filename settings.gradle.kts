pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        
        val localProps = java.util.Properties().apply {
            val propsFile = File(rootDir, "local.properties")
            if (propsFile.exists()) {
                propsFile.inputStream().use { load(it) }
            }
        }
        val gprUser: String = (localProps.getProperty("gpr.user") ?: System.getenv("GPR_USER")) ?: ""
        val gprKey: String = (localProps.getProperty("gpr.key") ?: System.getenv("GPR_KEY")) ?: ""

        maven {
            url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
            credentials {
                username = gprUser
                password = gprKey
            }
        }
    }
}

rootProject.name = "Karoo HA extension"
include(":app")
 