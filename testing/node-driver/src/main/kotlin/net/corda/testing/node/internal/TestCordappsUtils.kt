package net.corda.testing.node.internal

import net.corda.core.internal.cordapp.*
import net.corda.core.internal.cordapp.CordappImpl.Companion.CORDAPP_CONTRACT_NAME
import net.corda.core.internal.cordapp.CordappImpl.Companion.CORDAPP_CONTRACT_VERSION
import net.corda.core.internal.cordapp.CordappImpl.Companion.CORDAPP_WORKFLOW_NAME
import net.corda.core.internal.cordapp.CordappImpl.Companion.CORDAPP_WORKFLOW_VERSION
import net.corda.core.internal.cordapp.CordappImpl.Companion.TARGET_PLATFORM_VERSION
import net.corda.testing.node.TestCordapp
import java.io.InputStream
import java.util.jar.Attributes
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import kotlin.reflect.KClass

@JvmField
val FINANCE_CORDAPP: TestCordappImpl = findCordapp("net.corda.finance")

/**
 * Find the single CorDapp jar on the current classpath which contains the given package.
 *
 * This is a convenience method for [TestCordapp.Factory.findCordapp] but returns the internal [TestCordappImpl].
 */
fun findCordapp(packageName: String): TestCordappImpl = TestCordapp.Factory.findCordapp(packageName) as TestCordappImpl

/** Create a *custom* CorDapp which contains all the classes and resoures located in the given packages. */
fun cordappWithPackages(vararg packageNames: String): CustomCordapp = cordappWithPackages(packageNames.asList())

/** Create a *custom* CorDapp which contains all the classes and resoures located in the given packages. */
fun cordappWithPackages(packageNames: Iterable<String>): CustomCordapp = CustomCordapp(packages = simplifyScanPackages(packageNames))

/** Create a *custom* CorDapp which contains just the given classes. */
fun cordappWithClasses(vararg classes: Class<*>): CustomCordapp = CustomCordapp(packages = emptySet(), classes = classes.toSet())

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
