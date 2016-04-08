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

import groovy.transform.PackageScope
import com.redhat.ceylon.gradle.android.api.AndroidCeylonSourceSet
import com.redhat.ceylon.gradle.android.internal.AndroidCeylonSourceSetFactory
import org.gradle.api.Action
import org.gradle.api.NamedDomainObjectContainer
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.internal.file.FileResolver
import org.gradle.internal.reflect.Instantiator
import org.gradle.util.ConfigureUtil
//import com.athaydes.gradle.ceylon.task.CompileCeylonTask

/**
 * Configuration specific to the Groovy+Android plugin.
 */
class CeylonAndroidExtension {

  /**
   * Setting this flag to true will have only groovyc run instead of javac then groovyc run
   * This will effectively have all code (java and groovy) be joint compiled. This is
   * useful for adding groovy into older projects, and for having generated
   * code be able to utilize groovy code.
   *
   * @param skipJavaC
   */
  boolean skipJavaC

  final NamedDomainObjectContainer<AndroidCeylonSourceSet> sourceSetsContainer

  private Closure<Void> configClosure

  CeylonAndroidExtension(Project project, Instantiator instantiator, FileResolver fileResolver) {
    sourceSetsContainer = project.container(AndroidCeylonSourceSet, new AndroidCeylonSourceSetFactory(instantiator, fileResolver))

    sourceSetsContainer.whenObjectAdded { AndroidCeylonSourceSet sourceSet ->
      sourceSet.ceylon
    }
  }

  void options(Closure<Void> config) {
    configClosure = config
  }

  void sourceSets(Action<NamedDomainObjectContainer<AndroidCeylonSourceSet>> configClosure) {
    configClosure.execute(sourceSetsContainer)
  }

  @PackageScope void configure(/*CompileCeylon*/Task task) {
    if (configClosure != null) {
      ConfigureUtil.configure(configClosure, task)
    }
  }
}
