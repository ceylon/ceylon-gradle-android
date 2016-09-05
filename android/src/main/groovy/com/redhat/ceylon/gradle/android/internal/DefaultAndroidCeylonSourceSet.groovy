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

package com.redhat.ceylon.gradle.android.internal

import groovy.transform.CompileStatic
import com.redhat.ceylon.gradle.android.api.AndroidCeylonSourceSet
import org.gradle.api.file.SourceDirectorySet
import org.gradle.api.internal.file.DefaultSourceDirectorySet
import org.gradle.api.internal.file.FileResolver
import org.gradle.util.ConfigureUtil

@CompileStatic
class DefaultAndroidCeylonSourceSet implements AndroidCeylonSourceSet {
  final String name
  final SourceDirectorySet ceylon
  final SourceDirectorySet allCeylon

  DefaultAndroidCeylonSourceSet(String displayName, FileResolver fileResolver) {
    name = displayName

    ceylon = new DefaultSourceDirectorySet("$displayName Ceylon source", fileResolver)
    ceylon.filter.include("**/*.java", "**/*.ceylon")
    allCeylon = new DefaultSourceDirectorySet(String.format("%s Ceylon source", displayName), fileResolver)
    allCeylon.source(ceylon)
    allCeylon.filter.include("**/*.java", "**/*.ceylon")
  }

  @Override AndroidCeylonSourceSet ceylon(Closure configureClosure) {
    ConfigureUtil.configure(configureClosure, ceylon)
    return this
  }
}
