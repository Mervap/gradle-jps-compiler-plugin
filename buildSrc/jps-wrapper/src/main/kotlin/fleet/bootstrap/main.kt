package fleet.bootstrap

import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.util.io.URLUtil.*
import org.jetbrains.jps.api.CmdlineRemoteProto
import org.jetbrains.jps.build.Standalone
import org.jetbrains.jps.builders.java.JavaModuleBuildTargetType
import org.jetbrains.jps.incremental.messages.BuildMessage
import org.jetbrains.jps.model.JpsElementFactory
import org.jetbrains.jps.model.JpsGlobal
import org.jetbrains.jps.model.JpsModel
import org.jetbrains.jps.model.java.JpsJavaClasspathKind
import org.jetbrains.jps.model.java.JpsJavaExtensionService
import org.jetbrains.jps.model.java.JpsJavaSdkType
import org.jetbrains.jps.model.library.JpsOrderRootType
import org.jetbrains.jps.model.module.JpsLibraryDependency
import org.jetbrains.jps.model.serialization.JpsModelSerializationDataService
import org.jetbrains.jps.model.serialization.JpsProjectLoader
import java.io.File
import kotlin.system.exitProcess

fun main() {
    val model = initializeModel()

    val scopes =
            mutableListOf<CmdlineRemoteProto.Message.ControllerMessage.ParametersMessage.TargetTypeBuildScope>()
    JavaModuleBuildTargetType.ALL_TYPES.forEach { moduleType ->
        val builder =
                CmdlineRemoteProto.Message.ControllerMessage.ParametersMessage.TargetTypeBuildScope.newBuilder()
                        .setTypeId(moduleType.typeId).setForceBuild(Properties.forceRebuild)
        scopes.add(builder.addAllTargetId(listOf(Properties.moduleName)).build())
    }

    val jdkTable = File(Properties.jdkTable).readLines().map {
        val (name, path) = it.split("=")
        name to path
    }.toMap()

    val currentJdk = getCurrentJdk()
    model.project.modules.mapNotNull { it.getSdkReference(JpsJavaSdkType.INSTANCE)?.sdkName }.distinct()
            .forEach { sdkName ->
                val jdkHomePath = jdkTable[sdkName]
                if (jdkHomePath == null) {
                    println("SDK '$sdkName' not specified. Using current JDK ($currentJdk) as fallback.")
                } else {
                    println("Using $jdkHomePath for '$sdkName' jdk.")
                }
                addJdk(model.global, sdkName, jdkHomePath ?: currentJdk)
                readModulesFromReleaseFile(model, sdkName, jdkHomePath ?: currentJdk)
            }

    runBuild(model, scopes, Properties.moduleName)
}

private fun runBuild(model: JpsModel, scopes: MutableList<CmdlineRemoteProto.Message.ControllerMessage.ParametersMessage.TargetTypeBuildScope>, mainModule: String) {
    try {
        Standalone.runBuild({ model }, File(Properties.dataStorageRoot), { msg ->
            println(msg)
            if (msg.kind == BuildMessage.Kind.ERROR || msg.kind == BuildMessage.Kind.INTERNAL_BUILDER_ERROR) exitProcess(1)
        }, scopes, true)
        saveRuntimeClasspath(model, mainModule)
    } catch (e: Exception) {
        e.printStackTrace()
        exitProcess(1)
    } finally {
        exitProcess(0)
    }
}

private fun saveRuntimeClasspath(model: JpsModel, mainModule: String) {
    val mainJpsModule = model.project.modules.find { it.name == mainModule } ?: error("Module $mainModule not found.")
    val enumerator = JpsJavaExtensionService.dependencies(mainJpsModule)
            .recursively()
            .withoutSdk()
            .includedIn(JpsJavaClasspathKind.PRODUCTION_RUNTIME)

    val m2Deps = enumerator.libraries
            .flatMapTo(mutableSetOf()) { library -> library.getFiles(JpsOrderRootType.COMPILED) }
            .filter { it.name.endsWith(".jar") }

    val compilationOutputs = enumerator.satisfying { it !is JpsLibraryDependency }.classes().roots

    File(Properties.classpathOutputFilePath).writeText((compilationOutputs + m2Deps).joinToString(":"))
}

private fun initializeModel(): JpsModel {
    val model: JpsModel = JpsElementFactory.getInstance().createModel()

    val pathVariablesConfiguration = JpsModelSerializationDataService.getOrCreatePathVariablesConfiguration(model.global)
    pathVariablesConfiguration.addPathVariable("KOTLIN_BUNDLED", "${Properties.kotlinHome}/kotlinc")
    pathVariablesConfiguration.addPathVariable("MAVEN_REPOSITORY", File(System.getProperty("user.home"), ".m2/repository").absolutePath)

    val pathVariables = JpsModelSerializationDataService.computeAllPathVariables(model.global)
    JpsProjectLoader.loadProject(model.project, pathVariables, Properties.projectPath)
    return model
}

private fun addJdk(global: JpsGlobal, jdkName: String, jdkHomePath: String) {
    val sdk = JpsJavaExtensionService.getInstance().addJavaSdk(global, jdkName, jdkHomePath)
    val toolsJar = File(jdkHomePath, "lib/tools.jar")
    if (toolsJar.exists()) {
        sdk.addRoot(toolsJar, JpsOrderRootType.COMPILED)
    }
}

private fun readModulesFromReleaseFile(model: JpsModel, sdkName: String, sdkHome: String) {
    val additionalSdk = model.global.libraryCollection.findLibrary(sdkName)!!
    val urls = additionalSdk.getRoots(JpsOrderRootType.COMPILED).map { it.url }
    readModulesFromReleaseFile(File(sdkHome)).forEach {
        if (!urls.contains(it)) {
            additionalSdk.addRoot(it, JpsOrderRootType.COMPILED)
        }
    }
}

/**
 * Code is copied from com.intellij.openapi.projectRoots.impl.JavaSdkImpl#findClasses(java.io.File, boolean)
 */
private fun readModulesFromReleaseFile(jbrBaseDir: File) : List<String> {
    val releaseFile = File(jbrBaseDir, "release")
    if (!releaseFile.exists()) return emptyList()
    releaseFile.bufferedReader().use { stream ->
        val p = java.util.Properties()
        p.load(stream)
        val jbrBaseUrl = JRT_PROTOCOL + SCHEME_SEPARATOR +
                FileUtil.toSystemIndependentName(jbrBaseDir.absolutePath) +
                JAR_SEPARATOR
        val modules = p.getProperty("MODULES")
        return if (modules != null) {
            StringUtil.split(StringUtil.unquoteString(modules), " ").map { jbrBaseUrl + it }
        }
        else {
            emptyList()
        }
    }
}



private fun getCurrentJdk(): String {
    val javaHome = System.getProperty("java.home")
    if (File(javaHome).name == "jre") {
        return File(javaHome).parent
    }

    return javaHome
}