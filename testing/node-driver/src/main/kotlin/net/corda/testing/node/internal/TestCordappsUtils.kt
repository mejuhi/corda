package net.corda.testing.node.internal

import io.github.classgraph.ClassGraph
import net.corda.core.internal.cordapp.*
import net.corda.core.internal.cordapp.CordappImpl.Companion.CORDAPP_CONTRACT_NAME
import net.corda.core.internal.cordapp.CordappImpl.Companion.CORDAPP_CONTRACT_VERSION
import net.corda.core.internal.cordapp.CordappImpl.Companion.CORDAPP_WORKFLOW_NAME
import net.corda.core.internal.cordapp.CordappImpl.Companion.CORDAPP_WORKFLOW_VERSION
import net.corda.core.internal.cordapp.CordappImpl.Companion.TARGET_PLATFORM_VERSION
import net.corda.core.internal.outputStream
import net.corda.testing.node.TestCordapp
import java.io.InputStream
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import kotlin.reflect.KClass

@JvmField
val FINANCE_CORDAPP: TestCordappImpl = cordappForPackages("net.corda.finance")

/** Creates a [TestCordappImpl] for each package. */
fun cordappsForPackages(vararg packageNames: String): List<TestCordappImpl> = cordappsForPackages(packageNames.asList())

fun cordappsForPackages(packageNames: Iterable<String>): List<TestCordappImpl> {
    return simplifyScanPackages(packageNames).map { cordappForPackages(it) }
}

/** Creates a single [TestCordappImpl] containing all the given packges. */
fun cordappForPackages(vararg packageNames: String): TestCordappImpl {
    return TestCordapp.Factory.fromPackages(*packageNames) as TestCordappImpl
}

fun cordappForClasses(vararg classes: Class<*>): TestCordappImpl = cordappForPackages().withClasses(*classes)

fun getCallerClass(directCallerClass: KClass<*>): Class<*>? {
    val stackTrace = Throwable().stackTrace
    val index = stackTrace.indexOfLast { it.className == directCallerClass.java.name }
    if (index == -1) return null
    return try {
        Class.forName(stackTrace[index + 1].className)
    } catch (e: ClassNotFoundException) {
        null
    }
}

fun getCallerPackage(directCallerClass: KClass<*>): String? = getCallerClass(directCallerClass)?.`package`?.name

/**
 * Squashes child packages if the parent is present. Example: ["com.foo", "com.foo.bar"] into just ["com.foo"].
 */
fun simplifyScanPackages(scanPackages: Iterable<String>): Set<String> {
    return scanPackages.sorted().fold(emptySet()) { soFar, packageName ->
        when {
            soFar.isEmpty() -> setOf(packageName)
            packageName.startsWith("${soFar.last()}.") -> soFar
            else -> soFar + packageName
        }
    }
}

fun TestCordappImpl.packageAsJar(file: Path) {
    // Don't mention "classes" in the error message as that feature is only available internally
    require(packages.isNotEmpty() || classes.isNotEmpty()) { "At least one package must be specified" }

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

private val epochFileTime = FileTime.from(Instant.EPOCH)

private fun testEntry(name: String): ZipEntry {
    return ZipEntry(name).setCreationTime(epochFileTime).setLastAccessTime(epochFileTime).setLastModifiedTime(epochFileTime)
}

/** Add a new entry using the entire remaining bytes of [input] for the entry content. [input] will be closed at the end. */
fun JarOutputStream.addEntry(entry: ZipEntry, input: InputStream) {
    addEntry(entry) { input.use { it.copyTo(this) } }
}

inline fun JarOutputStream.addEntry(entry: ZipEntry, write: () -> Unit) {
    putNextEntry(entry)
    write()
    closeEntry()
}

fun createTestManifest(name: String, versionId: Int, targetPlatformVersion: Int): Manifest {
    val manifest = Manifest()

    // Mandatory manifest attribute. If not present, all other entries are silently skipped.
    manifest[Attributes.Name.MANIFEST_VERSION] = "1.0"

    manifest[CORDAPP_CONTRACT_NAME]  = name
    manifest[CORDAPP_CONTRACT_VERSION] = versionId.toString()
    manifest[CORDAPP_WORKFLOW_NAME]  = name
    manifest[CORDAPP_WORKFLOW_VERSION] = versionId.toString()
    manifest[TARGET_PLATFORM_VERSION] = targetPlatformVersion.toString()

    return manifest
}
