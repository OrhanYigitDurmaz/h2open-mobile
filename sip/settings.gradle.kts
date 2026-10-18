// Lets the SIP client build and test on its own (`cd sip && ../gradlew test`),
// without the Android SDK. The main build ignores this file.
dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "sip"
