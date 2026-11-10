plugins {
    // Non-remapping Loom: Minecraft 26.1+ is unobfuscated, so there is no mappings line
    // and dependencies use implementation instead of modImplementation.
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
}

version = providers.gradleProperty("mod_version").get()
group = providers.gradleProperty("maven_group").get()

base {
    archivesName.set(providers.gradleProperty("archives_base_name"))
}

loom {
    // Keeps client-only code (screens, rendering) in src/client so it can't leak into the server.
    splitEnvironmentSourceSets()

    mods {
        register("craftysearch") {
            sourceSet(sourceSets["main"])
            sourceSet(sourceSets["client"])
        }
    }
}

repositories {
    // Mod Menu, for the settings button in its mod list.
    maven("https://maven.terraformersmc.com/releases/") {
        content { includeGroup("com.terraformersmc") }
    }
    // Claim mods, for the server's protection checks (compiled against only, never bundled).
    maven("https://api.modrinth.com/maven") {
        content { includeGroup("maven.modrinth") }
    }
    maven("https://maven.nucleoid.xyz/") {
        content { includeGroup("eu.pb4") }
    }
    // REI's Rectangle class, for its exclusion zones.
    maven("https://maven.shedaniel.me/") {
        content { includeGroup("me.shedaniel.cloth") }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${providers.gradleProperty("minecraft_version").get()}")
    implementation("net.fabricmc:fabric-loader:${providers.gradleProperty("loader_version").get()}")
    implementation("net.fabricmc.fabric-api:fabric-api:${providers.gradleProperty("fabric_api_version").get()}")

    // Optional: compiled against, not bundled. In the dev client too, so its button can be tested.
    val modMenu = "com.terraformersmc:modmenu:${providers.gradleProperty("modmenu_version").get()}"
    "clientCompileOnly"(modMenu) { isTransitive = false }
    "clientRuntimeOnly"(modMenu) { isTransitive = false }

    // Claim mods (server protection checks): compiled against, used only when installed.
    val opac = "maven.modrinth:open-parties-and-claims:${providers.gradleProperty("opac_version").get()}"
    val flan = "maven.modrinth:flan:${providers.gradleProperty("flan_version").get()}"
    val commonProtection = "eu.pb4:common-protection-api:${providers.gradleProperty("common_protection_version").get()}"
    compileOnly(opac) { isTransitive = false }
    compileOnly(flan) { isTransitive = false }
    compileOnly(commonProtection) { isTransitive = false }

    // Recipe viewers (keeping their item list clear of the storage panel): compiled against only.
    val jei = "maven.modrinth:jei:${providers.gradleProperty("jei_version").get()}"
    val rei = "maven.modrinth:rei:${providers.gradleProperty("rei_version").get()}"
    "clientCompileOnly"(jei) { isTransitive = false }
    "clientCompileOnly"(rei) { isTransitive = false }
    "clientCompileOnly"("me.shedaniel.cloth:basic-math:${providers.gradleProperty("basic_math_version").get()}") { isTransitive = false }

    // To try an integration in runClient: gradlew runClient -Pwith=jei (or rei, opac, flan, cpa; comma-separated).
    // Modrinth's Maven lists no dependencies, so each mod's required ones are named here.
    val with = providers.gradleProperty("with").orNull?.split(",")?.map { it.trim() }?.toSet() ?: emptySet()
    fun tryOut(vararg coordinates: String) = coordinates.forEach { runtimeOnly(it) { isTransitive = false } }
    if ("jei" in with) tryOut(jei, "maven.modrinth:mezzconfig:${providers.gradleProperty("mezzconfig_version").get()}")
    if ("rei" in with) tryOut(rei, "maven.modrinth:architectury-api:${providers.gradleProperty("architectury_version").get()}",
            "maven.modrinth:cloth-config:${providers.gradleProperty("cloth_config_version").get()}")
    if ("opac" in with) tryOut(opac, "maven.modrinth:forge-config-api-port:${providers.gradleProperty("forge_config_port_version").get()}")
    if ("flan" in with) tryOut(flan)
    if ("cpa" in with) tryOut(commonProtection)
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

tasks.processResources {
    val modVersion = project.version
    inputs.property("version", modVersion)
    filesMatching("fabric.mod.json") {
        expand("version" to modVersion)
    }
}

tasks.jar {
    // The license travels with the jar, named after the mod so it can't clash with another mod's.
    val archivesName = base.archivesName.get()
    from("LICENSE") {
        rename { "${it}_$archivesName" }
    }
}
