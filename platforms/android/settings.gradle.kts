pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "PodJSWatch"
include(":runtime", ":androidApp", ":wearApp")
project(":runtime").projectDir = file("runtime")
project(":androidApp").projectDir = file("app")
project(":wearApp").projectDir = file("../wearos/app")
