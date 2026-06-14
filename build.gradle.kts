plugins {
    java
}

group = "dev.kezhall"
version = "1.2.0"

repositories {
    mavenCentral()
    maven {
        name = "papermc"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.+")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}

// Build the jar and copy it into your server's plugins folder.
// Default targets the server on your Desktop. Override with:
//   ./gradlew deployToServer -PserverPluginsDir=/path/to/plugins
val serverPluginsDir = (project.findProperty("serverPluginsDir") as String?)
    ?: "${System.getProperty("user.home")}/Desktop/Minecraft Server 26/plugins"

val deployToServer by tasks.registering(Copy::class) {
    group = "deployment"
    description = "Build the jar and copy it into the server's plugins folder."
    dependsOn(tasks.jar)
    from(tasks.jar.flatMap { it.archiveFile })
    into(serverPluginsDir)
}
