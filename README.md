Ceylon language support for Android
===================================

This plugin adds support for writing Android applications using the [Ceylon language](http://ceylon-lang.org).

This plugin is based on the excellent Groovy Android plugin at https://github.com/groovy/groovy-android-gradle-plugin.

Usage
-----

Edit your `build.gradle` file with the following:

```groovy
buildscript {
    repositories {
        jcenter()
    }
    dependencies {
        classpath 'com.android.tools.build:gradle:1.5.0'
        classpath 'com.redhat.ceylon.gradle:android:0.0.1-SNAPSHOT'
    }
}

apply plugin: 'com.redhat.ceylon.gradle.android'
```

then use the `assembleDebug` task to test.

Should you want to test development versions of the plugin, you can add the snapshot repository and depend on a SNAPSHOT:

```groovy
buildscript {
    repositories {
        jcenter()
        maven {
            url = 'http://oss.jfrog.org/artifactory/oss-snapshot-local'
        }
    }
    dependencies {
        classpath 'com.android.tools.build:gradle:1.5.0'
        classpath 'com.redhat.ceylon.gradle:android:0.0.1-SNAPSHOT'
    }
}
```

Where to put sources?
---------------------

Ceylon sources may be placed in `src/main/ceylon`, `src/test/ceylon`, `src/androidTest/ceylon` and any `src/${buildVariant}/ceylon` 
configured buy default. A default project will have the `release` and `debug` variants but these can be configured with build
types and flavors. See the [android plugin docs](https://sites.google.com/a/android.com/tools/tech-docs/new-build-system/user-guide#TOC-Build-Types)
for more about configuring different build variants.

Writing Ceylon code
-------------------

This plugin has been successfully tested with Android Studio and will make no attempts to add support for other IDEs.
This plugin will let you write an application in Ceylon.

Configuring the Ceylon compilation options
------------------------------------------

The Ceylon compilation tasks can be configured in the `androidCeylon` block using the `options` block:

```groovy
androidCeylon {
  options {
    sourceCompatibility	= '1.7' // as of 0.0.1 these are automatically set based off the android plugin's
    targetCompatibility = '1.7'
  }
}
```
