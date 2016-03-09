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
import com.android.sdklib.IAndroidTarget
import com.google.common.base.Suppliers
import org.gradle.api.artifacts.ResolvedDependency
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.SourceTask
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.AbstractCompile

import java.nio.file.Files
import java.nio.file.StandardCopyOption


class MyCeylonCompileTask extends AbstractCompile{

  def ceylonExecutable = "/home/stephane/src/java-eclipse/ceylon/dist/dist/bin/ceylon"

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

    def androidPlugin = CeylonAndroidPlugin.getAndroidBasePlugin(project)
    def androidJar = androidPlugin.androidBuilder.target.getPath(IAndroidTarget.ANDROID_JAR)
    def androidVersion = androidPlugin.androidBuilder.target.version.apiString

    Map<String, Dep> deps = new HashMap<>();
    def androidDep = new Dep()
    androidDep.version = androidVersion
    androidDep.name = "android"
    androidDep.jar = new File(androidJar)
    deps.put("android/"+androidVersion, androidDep)
    conf.resolvedConfiguration.firstLevelModuleDependencies.each{ dep -> importDependency(dep, deps, androidVersion) }

    importJarRepository(deps)
    runCompiler(androidVersion)
    runMlib()
    addMlibJarsToDex(deps)
  }

  void addMlibJarsToDex(Map<String, Dep> deps) {
    def mlib = new File(project.buildDir, "intermediates/ceylon-android/mlib")

    def resources = new File(project.buildDir, "intermediates/ceylon-android/resources")
    resources.mkdirs()

    def depLibs = new LinkedList<String>();
    for(dep in deps.values()){
      depLibs.add(dep.name+"-"+dep.version+".jar");
    }

    project.tasks.matching {
      it.name.contains('Dex') && it.variantName == variant.name
    }.each { dx ->
      def streamBuilder = new OriginalStream.Builder()

      def jarFiles = new LinkedList<File>()
      for(f in mlib.listFiles()){
        if(!f.name.endsWith(".jar"))
          continue
        if(depLibs.contains(f.name))
          continue
        // FIXME: configurable
        if(f.name.startsWith("com.redhat.ceylon.maven-support-"))
          continue;
        jarFiles.add(f)
        // FIXME: configurable
        if(f.name.startsWith("com.redhat.ceylon.model-"))
          extractResources(f, "com/redhat/ceylon/model/cmr/package-list*", resources)
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
      def copyTask = project.tasks.create("Extract resources from lib ${fromJar.name}", Copy)
      copyTask.from(project.zipTree(fromJar))
      copyTask.include([includes])
      copyTask.destinationDir = targetFolder
      copyTask.execute()
  }

  def importJarRepository(Map<String, Dep> deps) {
    def imported = new HashSet<String>();

    while(imported.size() != deps.size()) {
      def toImport = deps.keySet().findAll { key -> !imported.contains(key) }
      // find all modules to import which have no dependencies left to import
      def canImport = toImport.findAll { key -> deps.get(key).dependencies.find { depKey -> !imported.contains(depKey) } == null }
      if(canImport.empty)
        throw new RuntimeException("Failed to find importable dependency from: "+deps)
      for (importableKey in canImport) {
        def dep = deps.get(importableKey)
        importJar(dep, deps);
        imported.add(importableKey);
      }
    }
  }

  def importJar(Dep dep, Map<String, Dep> deps) {
    def androidRepo = new File(project.buildDir, "intermediates/ceylon-android/repository")
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
      def jarTask = project.tasks.create("Rejar ${dep.name}", Jar)
      jarTask.from(project.zipTree(dep.jar))
      def includes = dep.resourcePackage.replace('.', '/')+"/*.class"
      jarTask.from(project.fileTree(dir: resourcesDir, includes: [includes]))
      def rejarTarget = new File(rejarDir, "${dep.name}-${dep.version}.jar")
      jarTask.archiveName = rejarTarget.name
      jarTask.destinationDir = rejarDir
      jarTask.execute()
      jarFile = rejarTarget
    }

    List<String> args = new ArrayList<>()
    args.add(ceylonExecutable)
    args.add("import-jar")
    args.add("--out")
    args.add(androidRepo.absolutePath)
    if(descriptorFile.exists()){
      args.add("--descriptor")
      args.add(descriptorFile.absolutePath)
    }
    args.add(dep.name+"/"+dep.version)
    args.add(jarFile.absolutePath)

    ProcessBuilder pb = new ProcessBuilder(args)
    pb.redirectError(ProcessBuilder.Redirect.INHERIT)
    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)
    pb.redirectInput(ProcessBuilder.Redirect.INHERIT)
    Process p = pb.start()
    if(p.waitFor() != 0){
      // FIXME: type?
      throw new Exception("compile FAIL")
    }

  }

  def importDependency(ResolvedDependency dep, Map<String, Dep> deps, String androidVersion){
    def ceylonModuleName = dep.moduleGroup+"."+dep.moduleName
    def key = ceylonModuleName + "/" + dep.moduleVersion
    if(deps.containsKey(key))
      return;
    def newDep = new Dep();
    deps.put(key, newDep)
    newDep.name = ceylonModuleName
    newDep.version = dep.moduleVersion
    for(dep2 in dep.children){
      String depKey = "${dep2.moduleGroup}.${dep2.moduleName}/${dep2.moduleVersion}"
      newDep.dependencies.add(depKey)
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
    dep.children.each{ dep2 -> importDependency(dep2, deps, androidVersion) }
  }

  def runCompiler(androidVersion) {
    def androidRepo = new File(project.buildDir, "intermediates/ceylon-android/repository")
    def outputRepo = new File(project.buildDir, "intermediates/ceylon-android/modules")
    List<String> args = new ArrayList<>()
    // FIXME: prepopulate ceylon repo androidRepo
    // FIXME: hand over to Ceylon compiler plugin
    args.add(ceylonExecutable)
    args.add("compile")
    args.add("--rep")
    args.add(androidRepo.absolutePath)
    args.add("--out")
    args.add(outputRepo.absolutePath)
    args.add("--jdk-provider")
    args.add("android/"+androidVersion)
    for(foo in sourceFolders){
      project.logger.info("Source folder: "+foo)
      args.add("--src")
      args.add(foo.absolutePath)
    }
    ProcessBuilder pb = new ProcessBuilder(args)
    pb.redirectError(ProcessBuilder.Redirect.INHERIT)
    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)
    pb.redirectInput(ProcessBuilder.Redirect.INHERIT)
    Process p = pb.start()
    if(p.waitFor() != 0){
      // FIXME: type?
      throw new Exception("compile FAIL")
    }
  }

  def runMlib() {
    def androidRepo = new File(project.buildDir, "intermediates/ceylon-android/repository")
    def outputRepo = new File(project.buildDir, "intermediates/ceylon-android/mlib")
    def modulesRepo = new File(project.buildDir, "intermediates/ceylon-android/modules")
    List<String> args = new ArrayList<>()
    // FIXME: hand over to Ceylon compiler plugin
    args.add(ceylonExecutable)
    args.add("jigsaw")
    args.add("create-mlib")
    args.add("--static-metamodel")
    args.add("--rep")
    args.add(modulesRepo.absolutePath)
    args.add("--rep")
    args.add(androidRepo.absolutePath)
    args.add("--out")
    args.add(outputRepo.absolutePath)
    // FIXME: figure that one out
    args.add("fr.epardaud.testjava.testjava/1")
    ProcessBuilder pb = new ProcessBuilder(args)
    pb.redirectError(ProcessBuilder.Redirect.INHERIT)
    pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)
    pb.redirectInput(ProcessBuilder.Redirect.INHERIT)
    Process p = pb.start()
    if(p.waitFor() != 0){
      // FIXME: type?
      throw new Exception("jigsaw FAIL")
    }
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
