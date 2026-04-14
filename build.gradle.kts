buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.1.0")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.22")
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}

// [Legacy Automation] 빌드 후 자동 설치 및 실행 하네스
tasks.register("runApp") {
    dependsOn(":apps:android:installDebug")
    doLast {
        exec {
            commandLine("adb", "shell", "am", "start", "-n", "com.viitor/com.viitor.MainActivity")
        }
    }
}
