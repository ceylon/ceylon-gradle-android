/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.redhat.ceylon.gradle.android

import com.redhat.ceylon.gradle.android.internal.DefaultAndroidCeylonSourceSet
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.BasePlugin
import com.android.build.gradle.api.AndroidSourceSet
import com.android.build.gradle.internal.VariantManager
import com.android.build.gradle.internal.variant.BaseVariantData
import com.android.build.gradle.internal.variant.BaseVariantOutputData
import com.android.builder.model.SourceProvider
import groovy.transform.CompileStatic
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.internal.HasConvention
import org.gradle.api.internal.file.FileResolver
import org.gradle.api.tasks.SourceTask
import org.gradle.internal.reflect.Instantiator

//import com.athaydes.gradle.ceylon.task.CompileCeylonTask

import javax.inject.Inject

/**
 * Adds support for building Android applications using the Ceylon language.
 */
class CeylonAndroidPlugin implements Plugin<Project> {

  public static final String ANDROID_CEYLON_EXTENSION_NAME = 'androidCeylon'

  private Project project
  private CeylonAndroidExtension extension

  private final Instantiator instantiator

  @Inject
  CeylonAndroidPlugin(Instantiator instantiator) {
    this.instantiator = instantiator
  }

  void apply(Project project) {
    this.project = project

    BasePlugin basePlugin = getAndroidBasePlugin(project)
    if (!basePlugin) {
        throw new GradleException('You must apply the Android plugin or the Android library plugin before using the ceylon-android plugin')
    }

    extension = project.extensions.create(ANDROID_CEYLON_EXTENSION_NAME, CeylonAndroidExtension, project, instantiator, fileResolver)

    androidExtension.sourceSets.all { AndroidSourceSet sourceSet ->
      if (sourceSet instanceof HasConvention) {
        def sourceSetName = sourceSet.name

        def sourceSetPath = project.file("src/$sourceSetName/ceylon")

        // add so Android Studio will recognize ceylon files can see these
        sourceSet.java.srcDirs.add(sourceSetPath)

        // create ceylon source set so we can access it later
        def ceylonSourceSet = extension.sourceSetsContainer.maybeCreate(sourceSetName)
        sourceSet.convention.plugins['ceylon'] = ceylonSourceSet
        def ceylonDirSet = ceylonSourceSet.ceylon
        ceylonDirSet.srcDir(sourceSetPath)

        project.logger.debug("Created ceylon sourceDirectorySet at $ceylonDirSet.srcDirs")
      }
    }

    project.afterEvaluate { Project afterProject ->
      def androidPlugin = getAndroidBasePlugin(afterProject)
      def variantManager = getVariantManager(androidPlugin)

      processVariantData(variantManager.variantDataList, androidExtension, androidPlugin)
    }
  }

  private void processVariantData(
      List<BaseVariantData<? extends BaseVariantOutputData>> variantDataList,
      BaseExtension androidExtension, BasePlugin androidPlugin) {
    project.logger.debug("%s", androidExtension)
    project.logger.debug("%s", androidPlugin)

    variantDataList.each { variantData ->
      def variantDataName = variantData.name
      project.logger.debug("Process variant [$variantDataName]")

      def javaTask = getJavaTask(variantData)
      if (javaTask == null) {
        project.logger.info("javaTask is missing for $variantDataName, so Groovy files won't be compiled for it")
        return
      }

      def taskName = javaTask.name.replace('Java', 'Ceylon')
      def ceylonTask = project.tasks.create(taskName, MyCeylonCompileTask)
      ceylonTask.variant = variantData

      // do before configuration so users can override / don't break backwards compatibility
      ceylonTask.targetCompatibility = javaTask.targetCompatibility
      ceylonTask.sourceCompatibility = javaTask.sourceCompatibility
      extension.configure(ceylonTask)

      ceylonTask.destinationDir = javaTask.destinationDir
      ceylonTask.description = "Compiles the $variantDataName in ceylon."
      ceylonTask.classpath = javaTask.classpath
      ceylonTask.setDependsOn(javaTask.dependsOn)
//      ceylonTask.ceylonClasspath = javaTask.classpath


      def additionalSourceFiles = getGeneratedSourceDirs(variantData)

      def providers = variantData.variantConfiguration.sortedSourceProviders
      providers.each { SourceProvider provider ->
        def thisPackage = null
        // FIXME: is this not already parsed somewhere?
        if(provider.manifestFile.exists()){
          def parser = new XmlSlurper()
          def root = parser.parse(provider.manifestFile)
          thisPackage = root.@package.toString()
        }
        def ceylonSourceSet = provider.convention.plugins['ceylon'] as DefaultAndroidCeylonSourceSet
        def ceylonSourceDirectorySet = ceylonSourceSet.ceylon
        ceylonTask.source(ceylonSourceDirectorySet)
        List<File> generatedSourceForOurPackage = new LinkedList<>()
        if(thisPackage != null){
          String path = thisPackage.replace('.','/')
          for(dir in additionalSourceFiles){
            generatedSourceForOurPackage.add(new File(dir, path))
          }
        }

        // Exclude any java files that may be included in both java and groovy source sets
        javaTask.exclude { file ->
          project.logger.debug("Exclude java file $file.file")
          project.logger.debug("Exclude against ceylon files $ceylonSourceSet.ceylon.files")
          file.file in ceylonSourceSet.ceylon.files || file.file in generatedSourceForOurPackage
        }

        if (extension.skipJavaC) {
          ceylonTask.source(*(javaTask.source.files as List))
          javaTask.exclude {
            true
          }
        }
      }


      project.logger.info("Variant data: "+variantData)
      project.logger.info("Additional source files: "+additionalSourceFiles)
      ceylonTask.source(*additionalSourceFiles)

      ceylonTask.doFirst { MyCeylonCompileTask task ->
        def androidRunTime = project.files(getRuntimeJars(androidPlugin, androidExtension))
        project.logger.debug("%s", androidRunTime)
        task.classpath = androidRunTime + javaTask.classpath
////        task.ceylonClasspath = task.classpath
        task.compile()
      }

      javaTask.finalizedBy(ceylonTask)
    }
  }

  @CompileStatic
  static BasePlugin getAndroidBasePlugin(Project project) {
    def plugin = project.plugins.findPlugin('android') ?:
        project.plugins.findPlugin('android-library')

    return plugin as BasePlugin
  }

  @CompileStatic
  private BaseExtension getAndroidExtension() {
    return project.extensions.getByName('android') as BaseExtension
  }

  private static getRuntimeJars(BasePlugin plugin, BaseExtension extension) {
    if (plugin.metaClass.getMetaMethod('getRuntimeJarList')) {
      return plugin.runtimeJarList
    } else if (extension.metaClass.getMetaMethod('getBootClasspath')) {
      return extension.bootClasspath
    }

    return plugin.bootClasspath
  }

  private static SourceTask getJavaTask(BaseVariantData baseVariantData) {
    if (baseVariantData.metaClass.getMetaProperty('javaCompileTask')) {
      return baseVariantData.javaCompileTask
    } else if (baseVariantData.metaClass.getMetaProperty('javaCompilerTask')) {
      return baseVariantData.javaCompilerTask
    }
    return null
  }

  @CompileStatic
  private static List<File> getGeneratedSourceDirs(BaseVariantData variantData) {
    def getJavaSourcesMethod = variantData.metaClass.getMetaMethod('getJavaSources')
    if (getJavaSourcesMethod.returnType.metaClass == objectArrayMetaClass) {
      return variantData.javaSources.findAll { it instanceof File } as List<File>
    }

    List<File> result = []

    if (variantData.scope.generateRClassTask != null) {
      result << variantData.scope.RClassSourceOutputDir
    }

    if (variantData.scope.generateBuildConfigTask != null) {
      result << variantData.scope.buildConfigSourceOutputDir
    }

    if (variantData.scope.getAidlCompileTask() != null) {
      result << variantData.scope.aidlSourceOutputDir
    }

    if (variantData.scope.globalScope.extension.dataBinding.enabled) {
      result << variantData.scope.classOutputForDataBinding
    }

    if (!variantData.variantConfiguration.renderscriptNdkModeEnabled
        && variantData.scope.renderscriptCompileTask != null) {
      result << variantData.scope.renderscriptSourceOutputDir
    }

    return result
  }

  private FileResolver getFileResolver() {
    return project.fileResolver
  }

  private static VariantManager getVariantManager(BasePlugin plugin) {
    return plugin.variantManager
  }

  private static MetaClass getObjectArrayMetaClass() {
    return Object[].metaClass
  }
}
