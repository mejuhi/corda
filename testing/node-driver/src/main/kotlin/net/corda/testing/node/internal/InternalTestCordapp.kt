package net.corda.testing.node.internal

import io.github.classgraph.ClassGraph
import net.corda.core.internal.*
import net.corda.core.utilities.contextLogger
import net.corda.core.utilities.debug
import net.corda.core.utilities.loggerFor
import net.corda.testing.core.internal.JarSignatureTestUtils.generateKey
import net.corda.testing.core.internal.JarSignatureTestUtils.signJar
import net.corda.testing.node.TestCordapp
import org.apache.commons.lang.SystemUtils
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import kotlin.streams.toList

interface InternalTestCordapp : TestCordapp {
    val signJar: Boolean
    val keyStorePath: Path?
    val jarFile: Path
    fun signed(keyStorePath: Path? = null): InternalTestCordapp
}

data class TestCordappImpl(
        override val scanPackage: String,
        override val config: Map<String, Any> = emptyMap(),
        override val signJar: Boolean = false,
        override val keyStorePath: Path? = null
) : TestCordapp, InternalTestCordapp {
    override fun signed(keyStorePath: Path?): TestCordappImpl = copy(signJar = true, keyStorePath = keyStorePath)

    override val jarFile: Path
        get() {
            val jars = TestCordappImpl.findJars(scanPackage)
            when (jars.size) {
                0 -> throw IllegalArgumentException("Package $scanPackage does not exist")
                1 -> return jars.first()
                else -> throw IllegalArgumentException("More than one jar found containing package $scanPackage: $jars")
            }
        }

    companion object {
        private val packageToRootPaths = ConcurrentHashMap<String, Set<Path>>()
        private val projectRootToBuiltJar = ConcurrentHashMap<Path, Path>()

        fun findJars(scanPackage: String): Set<Path> {
            val rootPaths = findRootPaths(scanPackage)
            return if (rootPaths.all { it.toString().endsWith(".jar") }) {
                // We don't need to do anything more if all the root paths are jars
                rootPaths
            } else {
                // Otherwise we need to do build those paths which are local projects and extract the built jar from them
                rootPaths.mapTo(HashSet()) { if (it.toString().endsWith(".jar")) it else buildCordappJar(it) }
            }
        }

        private fun findRootPaths(scanPackage: String): Set<Path> {
            return packageToRootPaths.computeIfAbsent(scanPackage) {
                ClassGraph()
                        .whitelistPackages(scanPackage)
                        .scan()
                        .use { it.allResources }
                        .mapTo(HashSet()) { resource ->
                            val path = resource.classpathElementURL.toPath()
                            if (path.toString().endsWith(".jar")) path else findProjectRoot(path)
                        }
            }
        }

        private fun findProjectRoot(path: Path): Path {
            var current = path
            while (true) {
                if ((current / "build.gradle").exists()) {
                    return current
                }
                current = current.parent
            }
        }

        private fun buildCordappJar(projectRoot: Path): Path {
            return projectRootToBuiltJar.computeIfAbsent(projectRoot) {
                val gradlew = findGradlewDir(projectRoot) / (if (SystemUtils.IS_OS_WINDOWS) "gradlew.bat" else "gradlew")
                DriverDSLImpl.log.info("Generating CorDapp jar from local project in $projectRoot")
                val beforeBuild = FileTime.from(Instant.now())
                val exitCode = ProcessBuilder(gradlew.toString(), "jar").directory(projectRoot.toFile()).inheritIO().start().waitFor()
                check(exitCode == 0) { "Unable to generate CorDapp jar from local project in $projectRoot ($exitCode)" }
                val libs = projectRoot / "build" / "libs"
                val jars = libs.list { it.filter { it.toString().endsWith(".jar") && it.lastModifiedTime() >= beforeBuild }.toList() }
                checkNotNull(jars.singleOrNull()) { "More than one jar file found in $libs" }
            }
        }

        private fun findGradlewDir(path: Path): Path {
            var current = path
            while (true) {
                if ((current / "gradlew").exists() && (current / "gradlew.bat").exists()) {
                    return current
                }
                current = current.parent
            }
        }
    }
}

data class CustomCordapp(
        val packages: Set<String>,
        val name: String = "custom-cordapp",
        val versionId: Int = 1,
        val targetPlatformVersion: Int = PLATFORM_VERSION,
        val classes: Set<Class<*>> = emptySet(),
        override val config: Map<String, Any> = emptyMap(),
        override val signJar: Boolean = false,
        override val keyStorePath: Path? = null
) : InternalTestCordapp {
    override fun signed(keyStorePath: Path?): CustomCordapp = copy(signJar = true, keyStorePath = keyStorePath)
    override val scanPackage: String get() = throw UnsupportedOperationException()

    fun withClasses(vararg classes: Class<*>): CustomCordapp {
        return copy(classes = classes.filter { clazz -> packages.none { clazz.name.startsWith("$it.") } }.toSet())
    }

    override val jarFile: Path get() = getJarFile(this)

    private fun packageAsJar(file: Path) {
        require(packages.isNotEmpty() || classes.isNotEmpty()) { "At least one package or class must be specified" }

        val scanResult = ClassGraph()
                .whitelistPackages(*packages.toTypedArray())
                .whitelistClasses(*classes.map { it.name }.toTypedArray())
                .scan()

        scanResult.use {
            JarOutputStream(file.outputStream()).use { jos ->
                jos.addEntry(testEntry(JarFile.MANIFEST_NAME)) {
                    createTestManifest(name, versionId, targetPlatformVersion).write(jos)
                }

                // The same resource may be found in different locations (this will happen when running from gradle) so just
                // pick the first one found.
                scanResult.allResources.asMap().forEach { path, resourceList ->
                    jos.addEntry(testEntry(path), resourceList[0].open())
                }
            }
        }
    }

    private fun testEntry(name: String): ZipEntry {
        return ZipEntry(name).setCreationTime(epochFileTime).setLastAccessTime(epochFileTime).setLastModifiedTime(epochFileTime)
    }

    companion object {
        private val logger = contextLogger()
        private val epochFileTime = FileTime.from(Instant.EPOCH)
        private val cordappsDirectory = Paths.get("build").toAbsolutePath() / "generated-custom-cordapps" / getTimestampAsDirectoryName()
        private val whitespace = "\\s".toRegex()
        private val cache = ConcurrentHashMap<CustomCordapp, Path>()

        init {
            cordappsDirectory.createDirectories()
        }

        fun getJarFile(cordapp: CustomCordapp): Path {
            // Jar signing and config is not done on this jar but on a copy
            val key = cordapp.copy(signJar = false, keyStorePath = null, config = emptyMap())
            return cache.computeIfAbsent(key) {
                val filename = cordapp.run { "${name.replace(whitespace, "-")}_${versionId}_${targetPlatformVersion}_${UUID.randomUUID()}.jar" }
                val jarFile = cordappsDirectory / filename
                cordapp.packageAsJar(jarFile)
                logger.debug { "$cordapp packaged into $jarFile" }
                jarFile
            }
        }
    }
}

private val logger = loggerFor<InternalTestCordapp>()

fun signJar(cordapp: InternalTestCordapp, cordappsDirectory: Path, jarFile: Path) {
    if (cordapp.signJar) {
        val testKeystore = "_teststore"
        val alias = "Test"
        val pwd = "secret!"
        if (!(cordappsDirectory / testKeystore).exists() && (cordapp.keyStorePath == null)) {
            cordappsDirectory.generateKey(alias, pwd, "O=Test Company Ltd,OU=Test,L=London,C=GB")
        }
        val keyStorePathToUse = cordapp.keyStorePath ?: cordappsDirectory
        (keyStorePathToUse / testKeystore).copyTo(jarFile.parent / testKeystore)
        val pk = jarFile.parent.signJar(jarFile.fileName.toString(), alias, pwd)
        logger.debug { "Signed Jar: $jarFile with public key $pk" }
    } else {
        logger.debug { "Unsigned Jar: $jarFile" }
    }
}
