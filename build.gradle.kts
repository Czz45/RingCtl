buildscript {
    repositories {
        maven { url = uri("file:///root/localrepo") }
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.7.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10")
        classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.2.10")
    }
}
