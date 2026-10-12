// The server is its own Gradle build, separate from the Android app in the parent folder.
rootProject.name = "parkinson-server"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
