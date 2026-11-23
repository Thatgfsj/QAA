pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "QAA"

include(":app")
include(":core-inference")
include(":core-harness")
include(":core-tools")
include(":feature-normal-ui")
include(":feature-terminal-ui")
