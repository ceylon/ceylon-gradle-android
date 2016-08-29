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

import com.android.build.api.transform.QualifiedContent
import com.android.build.gradle.internal.pipeline.OriginalStream
import com.android.build.gradle.internal.variant.BaseVariantData
import com.android.build.gradle.internal.variant.BaseVariantOutputData
import com.android.builder.dependency.JarDependency
import com.android.sdklib.IAndroidTarget
import com.athaydes.gradle.ceylon.CeylonConfig
import com.athaydes.gradle.ceylon.util.CeylonRunner
import com.google.common.base.Suppliers
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.SourceTask
import org.gradle.api.Task
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.AbstractCompile

import java.nio.file.Files
import java.nio.file.StandardCopyOption

class MyCeylonCompileTask extends AbstractCompile{

  private List<File> sourceFolders = new ArrayList<>()
  BaseVariantData<? extends BaseVariantOutputData> variant;

  MyCeylonCompileTask(){
    // FIXME: we should figure this one out, but if we don't force this, since our outputs
    // are passed to android in pre-dexed, and android clears them, we can't tell they're missing
    outputs.upToDateWhen { false }
  }

  class Dep {
    String name;
    String version;
    File jar;
    Set<String> dependencies = new HashSet<>();
    boolean needsResourceClasses
    String resourcePackage

    @Override
    String toString() {
      return """
Module: ${name}/${version}
Jar: ${jar}
Depends: ${dependencies}
"""
    }
  }

  @Override
  protected void compile() {
    project.logger.info("Compiling Ceylon classes in "+ this.source.asPath)
    project.logger.info("Ceylon classpath is "+ classpath.asPath)
    project.logger.info("Ceylon source folders is "+ sourceFolders)

    def moduleFinder = new FileNameFinder();
    def foundModule = false;
    for(sourceFolder in sourceFolders){
      if(!moduleFinder.getFileNames(sourceFolder.absolutePath, '**/module.ceylon').isEmpty()){
        foundModule = true
        break
      }
    }

    if(!foundModule){
      project.logger.info("No source module found: we're done")
      return;
    }
    def conf = project.configurations.getByName("compile");
    def aptConf = project.configurations.findByName("apt");

    def androidPlugin = CeylonAndroidPlugin.getAndroidBasePlugin(project)
    def androidJar = androidPlugin.androidBuilder.target.getPath(IAndroidTarget.ANDROID_JAR)
    def androidVersion = androidPlugin.androidBuilder.target.version.apiString

    Map<String, Dep> deps = new HashMap<>()
    initDeps(deps, androidVersion, androidJar)
    Map<String, Dep> aptDeps = new HashMap<>()
    initDeps(aptDeps, androidVersion, androidJar)
    List<String> aptModules = new LinkedList<>();
    conf.resolvedConfiguration.firstLevelModuleDependencies.each{ dep -> importDependency(dep, deps, androidVersion, false) }
    if(aptConf != null){
      aptConf.resolvedConfiguration.firstLevelModuleDependencies.each { dep ->
        importDependency(dep, aptDeps, androidVersion, true)
        def ceylonModuleName = dep.moduleGroup + "." + dep.moduleName
        def key = ceylonModuleName + "/" + dep.moduleVersion
        aptModules.add(key);
      }
    }

    CeylonConfig ceylonConfig = project.extensions.findByName('ceylon') as CeylonConfig
    importJarRepository(deps, ceylonConfig, "repository")
    importJarRepository(aptDeps, ceylonConfig, "apt-repository")
    runCompiler(androidVersion, ceylonConfig, aptModules)
    runMlib(ceylonConfig)
    addMlibJarsToDex(deps)
  }

  def initDeps(HashMap<String, Dep> deps, String androidVersion, String androidJar) {
    def androidDep = new Dep()
    androidDep.version = androidVersion
    androidDep.name = "android"
    androidDep.jar = new File(androidJar)
    deps.put("android/"+androidVersion, androidDep)
  }

  void addMlibJarsToDex(Map<String, Dep> deps) {
    def mlib = new File(project.buildDir, "intermediates/ceylon-android/mlib")

    def resources = new File(project.buildDir, "intermediates/ceylon-android/resources")
    resources.mkdirs()

    def depLibs = new LinkedList<String>();
    for(dep in deps.values()){
      depLibs.add(dep.name+"-"+dep.version+".jar");
    }
    def dexTasks = new LinkedList<Task>();

    project.tasks.matching {
      it.name.contains('Dex') && !it.name.contains("DexMethods") && !it.name.contains("incremental") && it.variantName == variant.name
    }.each { dx ->
      dexTasks.add(dx)
    }

    for(f in mlib.listFiles()){
      if(f.name.endsWith(".jar") && !depLibs.contains(f.name) && f.name.startsWith("com.redhat.ceylon.model-"))
        extractResources(f, "com/redhat/ceylon/model/cmr/package-list*", resources)
    }

    dexTasks.each { dx ->
      def streamBuilder = new OriginalStream.Builder()

      def jarFiles = new LinkedList<File>()
      for(f in mlib.listFiles()){
        if(!f.name.endsWith(".jar"))
          continue
        if(depLibs.contains(f.name))
          continue
        jarFiles.add(f)

        // make sure we add it there for the linter to put it in the classpath
        this.variant.variantDependency.addJars(Arrays.asList(new JarDependency(f, true, true, null, null)));
      }
      streamBuilder.addScope(QualifiedContent.Scope.EXTERNAL_LIBRARIES)
      streamBuilder.addContentType(QualifiedContent.DefaultContentType.CLASSES)
      streamBuilder.setJars(Suppliers.ofInstance((Collection<File>) jarFiles))
      dx.consumedInputStreams.add(streamBuilder.build())
    }

    def metamodelSource = new File(mlib, "META-INF/ceylon/metamodel")
    def metamodelDest = new File(resources, "META-INF/ceylon/metamodel")
    metamodelDest.parentFile.mkdirs()
    Files.copy(metamodelSource.toPath(), metamodelDest.toPath(), StandardCopyOption.REPLACE_EXISTING)

    def streamBuilder = new OriginalStream.Builder()

    streamBuilder.addScope(QualifiedContent.Scope.PROJECT)
    streamBuilder.addContentType(QualifiedContent.DefaultContentType.RESOURCES)
    streamBuilder.setFolder(resources)
    variant.scope.transformManager.addStream(streamBuilder.build())
  }

  def extractResources(File fromJar, String includes, File targetFolder) {
      def copyTask = project.tasks.create("Extract resources from lib ${fromJar.name} for ${variant.name}", Copy)
      copyTask.from(project.zipTree(fromJar))
      copyTask.include([includes])
      copyTask.destinationDir = targetFolder
      // force it
      copyTask.outputs.upToDateWhen { false }
      copyTask.execute()
  }

  def importJarRepository(Map<String, Dep> deps, CeylonConfig conf, String repoName) {
    def imported = new HashSet<String>();

    while(imported.size() != deps.size()) {
      def toImport = deps.keySet().findAll { key -> !imported.contains(key) }
      // find all modules to import which have no dependencies left to import
      def canImport = toImport.findAll { key -> deps.get(key).dependencies.find { depKey -> !imported.contains(depKey) } == null }
      if(canImport.empty)
        throw new RuntimeException("Failed to find importable dependency from: "+deps)
      for (importableKey in canImport) {
        def dep = deps.get(importableKey)
        importJar(dep, deps, conf, repoName);
        imported.add(importableKey);
      }
    }
  }

  def importJar(Dep dep, Map<String, Dep> deps, CeylonConfig conf, String repoName) {
    System.err.println("Importing ${dep.name}/${dep.version}")
    def androidRepo = new File(project.buildDir, "intermediates/ceylon-android/${repoName}")
    def descriptorsDir = new File(project.buildDir, "intermediates/ceylon-android/descriptors")
    androidRepo.mkdirs()
    descriptorsDir.mkdirs()

    def descriptorFile = new File(descriptorsDir, "${dep.name}.properties")
    descriptorFile.delete()
    // FIXME: add JDK imports, but how?
    for(imp in dep.dependencies){
      def imported = deps.get(imp)
      // FIXME: deal with otionals
      descriptorFile.append("+"+imported.name+"="+imported.version+"\n")
    }

    def jarFile = dep.jar

    if(dep.needsResourceClasses){
      // we need to rejar it with resource classes
      def rejarDir = new File(project.buildDir, "intermediates/ceylon-android/jars")
      rejarDir.mkdirs()
      def resourcesDir = new File(project.buildDir, "intermediates/classes/debug")
      def jarTask = project.tasks.create("Rejar ${dep.name} for ${variant.name} in ${repoName}", Jar)
      jarTask.from(project.zipTree(dep.jar))
      def includes = dep.resourcePackage.replace('.', '/')+"/*.class"
      jarTask.from(project.fileTree(dir: resourcesDir, includes: [includes]))
      def rejarTarget = new File(rejarDir, "${dep.name}-${dep.version}.jar")
      jarTask.archiveName = rejarTarget.name
      jarTask.destinationDir = rejarDir
      // force it
      jarTask.outputs.upToDateWhen { false }
      jarTask.execute()
      jarFile = rejarTarget
    }

    def options = []

    options << "--out=${androidRepo.absolutePath}"

    if(conf.forceImports){
      options << "--force"
    }

    if(descriptorFile.exists()){
      options << "--descriptor=${descriptorFile.absolutePath}"
    }
    options << dep.name + "/" + dep.version
    options << jarFile.absolutePath

    CeylonRunner.run("import-jar", "", project, conf, options)
  }

  def importDependency(ResolvedDependency dep, Map<String, Dep> deps, String androidVersion, boolean forApt){
    def ceylonModuleName = dep.moduleGroup+"."+dep.moduleName
    def key = ceylonModuleName + "/" + dep.moduleVersion
    if(deps.containsKey(key))
      return;
    def newDep = new Dep();
    deps.put(key, newDep)
    newDep.name = ceylonModuleName
    newDep.version = dep.moduleVersion
    System.err.println("Importing dep "+dep+" for apt: "+forApt)
    for(dep2 in dep.children){
      String depKey = "${dep2.moduleGroup}.${dep2.moduleName}/${dep2.moduleVersion}"
      newDep.dependencies.add(depKey)
      System.err.println(" -> "+dep2)
    }
    newDep.dependencies.add("android/"+androidVersion)
    // FIXME: barf if there's more than one artifact
    for(art in dep.moduleArtifacts){
      if(art.type == "aar"){
        def explodedAar = new File(project.buildDir, "intermediates/exploded-aar/${dep.moduleGroup}/${dep.moduleName}/${dep.moduleVersion}")
        def explodedJars = new File(explodedAar, "jars")
        def explodedJar = new File(explodedJars, "classes.jar")
        def explodedResource = new File(explodedAar, "R.txt")
        // deal with resource files
        if(explodedResource.exists()){
          newDep.needsResourceClasses = true
          // what package is it?
          def explodedResourceAndroidXml = new File(explodedAar, "aapt/AndroidManifest.xml")
          def parser = new XmlSlurper()
          def root = parser.parse(explodedResourceAndroidXml)
          newDep.resourcePackage = root.@package
        }
        // deal with jars
        newDep.jar = explodedJar
        def explodedLibs = new File(explodedJars, "libs")
        if(explodedLibs.isDirectory()){
          for(lib in explodedLibs.listFiles()){
            // FIXME: check name
            // drop the ".jar"
            def libName = lib.name.substring(0, lib.name.length()-4)
            // it can end in -version, if yes drop it
            def suffix = "-${dep.moduleVersion}"
            if(libName.endsWith(suffix))
              libName = libName.substring(0, libName.length()-suffix.length())
            def ceylonLibName = ceylonModuleName + "." + libName
            newDep.dependencies.add(ceylonLibName+"/"+dep.moduleVersion)
            def libDep = new Dep();
            libDep.name = ceylonLibName
            libDep.version = dep.moduleVersion
            libDep.jar = lib
            libDep.dependencies.add("android/"+androidVersion)
            deps.put(ceylonLibName+"/"+dep.moduleVersion, libDep)
          }
        }
      }else{
        // FIXME: check we have a jar
        newDep.jar = art.file
      }
    }
    dep.children.each{ dep2 -> importDependency(dep2, deps, androidVersion, forApt) }
  }

  def runCompiler(androidVersion, CeylonConfig conf, List<String> aptModules) {
    def androidRepo = new File(project.buildDir, "intermediates/ceylon-android/repository")
    def aptRepo = new File(project.buildDir, "intermediates/ceylon-android/apt-repository")
    def outputRepo = new File(project.buildDir, "intermediates/ceylon-android/modules")
    List<String> args = new ArrayList<>()
    // FIXME: prepopulate ceylon repo androidRepo

    def options = []

    options << "--rep=${androidRepo.absolutePath}"
    options << "--out=${outputRepo.absolutePath}"
    options << "--jdk-provider=android/${androidVersion}"
    sourceFolders.each { options << "--src=$it.absolutePath" }
    aptModules.each { options << "--apt=$it" }
    if(!aptModules.empty)
      options << "--rep=${aptRepo.absolutePath}"

    CeylonRunner.run("compile", "", project, conf, options)
  }

  def runMlib(CeylonConfig conf) {
    def androidRepo = new File(project.buildDir, "intermediates/ceylon-android/repository")
    def outputRepo = new File(project.buildDir, "intermediates/ceylon-android/mlib")
    def modulesRepo = new File(project.buildDir, "intermediates/ceylon-android/modules")

    def options = []

    options << "--static-metamodel"
    options << "--rep=${modulesRepo.absolutePath}"
    options << "--rep=${androidRepo.absolutePath}"
    options << "--out=${outputRepo.absolutePath}"

    CeylonRunner.run("jigsaw create-mlib", conf.module, project, conf, options)
  }

  @Override
  SourceTask source(Object... sources) {
    for(foo in sources){
      if(foo instanceof File) {
        File f = foo
        if (f.directory && f.exists()) {
          sourceFolders.add(f)
        }
      }else if(foo instanceof SourceDirectorySet) {
        SourceDirectorySet f = foo
        for(dir in f.srcDirs){
          if (dir.directory && dir.exists()) {
            sourceFolders.addAll(f.srcDirs)
          }
        }
      }
    }
    return super.source(sources)
  }
}
