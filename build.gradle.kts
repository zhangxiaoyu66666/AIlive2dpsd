import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

plugins {
	kotlin("jvm") version "2.4.10"
	kotlin("plugin.serialization") version "2.4.10"
	id("org.jetbrains.compose") version "1.11.1"
	id("org.jetbrains.kotlin.plugin.compose") version "2.4.10"
	distribution
}

group = "io.github.psd2live"
version = "0.6.0"

kotlin {
	jvmToolchain(21)
}

val verifyAgentReferences = tasks.register("verifyAgentReferences") {
    val topics = listOf("rig-geometry", "assets", "variants", "face")
    val pairs = topics.map { topic ->
        file("src/main/resources/agent/skills/$topic.md") to file(".agent/skills/psd2live-rigging/references/$topic.md")
    } + (file("src/main/resources/agent/skills/hair-separation.md") to file(".agent/skills/hair-separation/SKILL.md"))
    inputs.files(pairs.flatMap { listOf(it.first, it.second) })
    doLast {
        for ((embedded, host) in pairs) {
            val body = host.readText().replace("\r\n", "\n").let {
                if (it.startsWith("---\n")) it.substringAfter("\n---\n") else it
            }.trim()
            check(embedded.readText().replace("\r\n", "\n").trim() == body) {
                "Agent reference drift: ${embedded.path} and ${host.path}"
            }
        }
    }
}
tasks.named("check") { dependsOn(verifyAgentReferences) }

dependencies {
	implementation(platform("io.ktor:ktor-bom:3.5.1"))
	// Core engine dependencies (ported from Umamo: format, runtime, interop, render, edit)
	implementation(kotlin("reflect"))
	implementation("org.jdom:jdom:1.1.3")
	implementation("com.squareup.okio:okio:3.17.0")
	implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
	implementation("app.cash.sqldelight:sqlite-driver:2.0.2")

	// LWJGL (OpenGL rendering pipeline)
	val lwjglNatives = run {
		val os = System.getProperty("os.name").lowercase()
		val arch = System.getProperty("os.arch").lowercase()
		val isArm = arch.startsWith("aarch64") || arch.startsWith("arm")
		when {
			os.contains("win") -> if (isArm) "natives-windows-arm64" else "natives-windows"
			os.contains("mac") || os.contains("darwin") -> if (isArm) "natives-macos-arm64" else "natives-macos"
			os.contains("linux") || os.contains("nix") -> if (isArm) "natives-linux-arm64" else "natives-linux"
			else -> "natives-windows"
		}
	}
	implementation(platform("org.lwjgl:lwjgl-bom:3.4.2"))
	implementation("org.lwjgl:lwjgl")
	implementation("org.lwjgl:lwjgl-opengl")
	implementation("org.lwjgl:lwjgl-nfd")
	runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
	runtimeOnly("org.lwjgl:lwjgl-opengl::$lwjglNatives")
	runtimeOnly("org.lwjgl:lwjgl-nfd::$lwjglNatives")
	implementation("io.modelcontextprotocol:kotlin-sdk-server:0.15.0")
	implementation("io.ktor:ktor-server-cio")
	implementation("io.ktor:ktor-server-auth")
	implementation("io.ktor:ktor-server-content-negotiation")
	implementation("io.ktor:ktor-server-sse")
	implementation("io.ktor:ktor-serialization-kotlinx-json")
	runtimeOnly("org.slf4j:slf4j-nop:2.0.17")
	implementation("net.java.dev.jna:jna:5.18.0")
	implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
	implementation(compose.desktop.currentOs)
	implementation("org.jetbrains.compose.runtime:runtime:1.11.1")
	implementation("org.jetbrains.compose.foundation:foundation:1.11.1")
	implementation("org.jetbrains.compose.ui:ui:1.11.1")
	implementation("org.jetbrains.compose.material:material:1.11.1")
	testImplementation(kotlin("test"))
	testImplementation("io.modelcontextprotocol:kotlin-sdk-client:0.15.0")
	implementation("io.ktor:ktor-client-cio")
}


distributions {
	main {
		contents {
			from("README.md")
			from("README_en.md")
			from("README_ja.md")
			from("LICENSE")
			from("THIRD_PARTY_NOTICES.md")
			from("licenses") { into("licenses") }
			from("docs") {
				into("docs")
				exclude("imgs/**")
			}
			from("src/main/resources/cubism") { into("cubism") }
		}
	}
}

val desktopDistributionRoot = layout.buildDirectory.dir(
    providers.gradleProperty("distributionOutputDir").orElse("compose/binaries")
)

compose.desktop {
	application {
		mainClass = "io.github.psd2live.MainKt"
		jvmArgs += listOf("-Xmx8g", "-Dfile.encoding=UTF-8")
		nativeDistributions {
            outputBaseDir.set(desktopDistributionRoot)
            // LWJGL loads sun.misc.Unsafe reflectively; jdeps cannot infer this dependency.
            modules("jdk.unsupported")
			targetFormats(
				org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
				org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
			)
			packageName = "PSD2Live"
			packageVersion = "0.6.0"
			description = "PSD2Live - Automated Live2D Rigging Pipeline"
			copyright = "© 2026 PSD2Live. Licensed under GPL-3.0."
			vendor = "PSD2Live"

			windows {
				menuGroup = "PSD2Live"
				upgradeUuid = "8e9c4b1a-2d3e-4f5a-6b7c-8d9e0f1a2b3c"
			}
		}
	}
}

afterEvaluate {
	tasks.withType<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>().configureEach {
		packageFromUberJar.set(true)
	}
	listOf("packageUberJarForCurrentOS", "packageReleaseUberJarForCurrentOS").forEach { taskName ->
		tasks.findByName(taskName)?.let { task ->
			if (task is org.gradle.jvm.tasks.Jar) {
				task.apply {
					exclude("org/sqlite/native/FreeBSD/**")
					exclude("org/sqlite/native/Linux/**")
					exclude("org/sqlite/native/Linux-Android/**")
					exclude("org/sqlite/native/Linux-Musl/**")
					exclude("org/sqlite/native/Mac/**")
					exclude("org/sqlite/native/Windows/aarch64/**")
					exclude("org/sqlite/native/Windows/armv7/**")
					exclude("org/sqlite/native/Windows/x86/**")
					exclude("com/sun/jna/aix*/**")
					exclude("com/sun/jna/darwin*/**")
					exclude("com/sun/jna/dragonflybsd*/**")
					exclude("com/sun/jna/freebsd*/**")
					exclude("com/sun/jna/linux*/**")
					exclude("com/sun/jna/openbsd*/**")
					exclude("com/sun/jna/sunos*/**")
					exclude("com/sun/jna/win32-aarch64/**")
					exclude("com/sun/jna/win32-x86/**")
				}
			}
		}
	}

	tasks.named("createDistributable").configure {
		doLast {
			val appDir = desktopDistributionRoot.get().dir("main/app/PSD2Live/app").asFile
			if (appDir.exists()) {
				copy {
                    from("LICENSE", "THIRD_PARTY_NOTICES.md", "docs/eye-rig-reliability.md", "docs/native-file-picker.md", "docs/project-tabs.md", "docs/source-workflow.md")
					into(appDir.parentFile)
				}
				copy {
					from("licenses")
					into(File(appDir.parentFile, "licenses"))
				}
				appDir.listFiles()?.forEach { jarFile ->
					if (jarFile.name.startsWith("sqlite-jdbc-") || jarFile.name.startsWith("jna-")) {
						val tempJar = File(jarFile.parentFile, jarFile.name + ".tmp")
						ZipFile(jarFile).use { zin ->
							ZipOutputStream(tempJar.outputStream().buffered()).use { zout ->
								val entries = zin.entries()
								while (entries.hasMoreElements()) {
									val entry = entries.nextElement()
									val p = entry.name.replace('\\', '/')
									var keep = true
									if (p.startsWith("org/sqlite/native/") && !p.startsWith("org/sqlite/native/Windows/x86_64/")) {
										keep = false
									}
									if (p.startsWith("com/sun/jna/") && (p.endsWith(".so") || p.endsWith(".dylib") || p.endsWith(".a") || p.endsWith(".jnilib")) && !p.contains("win32-x86-64")) {
										keep = false
									}
									if (p.startsWith("com/sun/jna/win32-") && !p.contains("win32-x86-64")) {
										keep = false
									}
									if (keep) {
										val newEntry = ZipEntry(entry.name).apply {
											time = entry.time
											comment = entry.comment
											if (entry.extra != null) {
												extra = entry.extra
											}
										}
										zout.putNextEntry(newEntry)
										zin.getInputStream(entry).copyTo(zout)
										zout.closeEntry()
									}
								}
							}
						}
						if (jarFile.delete()) {
							tempJar.renameTo(jarFile)
						} else {
							tempJar.delete()
						}
					}
				}
			}
		}
	}
}

tasks.test {
	useJUnitPlatform()
	systemProperty("psd2live.cubism.smoke", System.getProperty("psd2live.cubism.smoke", "false"))
}

// Opt-in acceptance with local PSD artwork; ordinary tests use synthetic rasters only.
tasks.register<JavaExec>("eyeRigVisualCheck") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.psd2live.core.EyeRigVisualCheck")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    maxHeapSize = "4g"
    systemProperty("java.awt.headless", "true")
    doFirst {
        args(providers.gradleProperty("eyeRigSource").get(),
            providers.gradleProperty("eyeRigOutput").getOrElse(layout.buildDirectory.dir("eye-rig-qa").get().asFile.absolutePath))
    }
}

// A full development JDK can mask missing modules in the shipped jlink image.
// Use its launcher only; -XXaltjvm selects the actual packaged JVM and module image.
val verifyPackagedFilePicker = tasks.register<JavaExec>("verifyPackagedFilePicker") {
    dependsOn(tasks.testClasses)
    mainClass.set("io.github.psd2live.ui.PackagedNativeProbe")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    onlyIf {
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true) &&
            tasks.named("createDistributable").get().state.failure == null
    }
    doFirst {
        val app = desktopDistributionRoot.get().dir("main/app/PSD2Live").asFile
        val runtime = File(app, "runtime")
        check(File(runtime, "bin/server/jvm.dll").isFile) { "Packaged JVM is missing: $runtime" }
        classpath = files(sourceSets["test"].output.classesDirs, fileTree(File(app, "app")) { include("*.jar") })
        jvmArgs("-XXaltjvm=${File(runtime, "bin/server").absolutePath}", "-Djava.awt.headless=true")
        args(runtime.absolutePath)
    }
}
afterEvaluate {
    tasks.named("createDistributable") { finalizedBy(verifyPackagedFilePicker) }
    verifyPackagedFilePicker.configure { dependsOn("createDistributable") }
}

// Opt-in live local inference. Never part of check; does not start or stop See-Through.
tasks.register<JavaExec>("sourceWorkflowPageCheck") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.psd2live.workflow.SourceWorkflowPageCheck")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    maxHeapSize = "4g"
    systemProperty("java.awt.headless", "true")
    doFirst {
        args(providers.gradleProperty("workflowImage").get(), providers.gradleProperty("workflowPsd").get(), providers.gradleProperty("workflowOutput").get())
    }
}

tasks.register<JavaExec>("sourceWorkflowLiveCheck") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.psd2live.workflow.SourceWorkflowLiveCheck")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    maxHeapSize = "4g"
    systemProperty("java.awt.headless", "true")
    doFirst {
        args(providers.gradleProperty("workflowImage").get(), providers.gradleProperty("workflowOutput").get())
        providers.gradleProperty("workflowResultUrl").orNull?.let { args(it) }
    }
}

// Actual Compose progress UI, driven by a controlled local HTTP fixture without GPU inference.
tasks.register<JavaExec>("workflowProgressPageCheck") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("io.github.psd2live.workflow.WorkflowProgressPageCheck")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    maxHeapSize = "4g"
    systemProperty("java.awt.headless", "true")
    doFirst {
        args(providers.gradleProperty("workflowImage").get(), providers.gradleProperty("workflowPsd").get(), providers.gradleProperty("workflowOutput").get())
    }
}
