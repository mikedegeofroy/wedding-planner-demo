plugins {
    java
    id("su.onno.widgets")
    id("org.springframework.boot") version "3.4.4"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.weddingplanner"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    maven {
        url = uri("https://cloud.onno.su/modules")
        content { includeGroup("su.onno") }
    }
}

val onnoVersion = providers.gradleProperty("onnoVersion").getOrElse("3.3.0")

// The widget workspace ships @onno/widget-sdk, React and Tailwind and nothing else, so a widget that
// imports an icon set fails to bundle. Declared here at the version the framework's own frontend
// uses, so a glyph looks the same in an app widget as it does in the shell around it.
onnoWidgets {
    npmDependencies.put("lucide-react", "^0.469.0")
}

dependencies {
    implementation("su.onno:onno-framework-starter:$onnoVersion")
    implementation("su.onno:onno-ui-starter:$onnoVersion")
    implementation("su.onno:onno-crm-starter:$onnoVersion")
    implementation("su.onno:onno-auth-starter:$onnoVersion")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jdbc")
    // Estimates leave this app as the spreadsheet clients already work in, so the workbook is a
    // real .xlsx with formats and column widths rather than a CSV renamed.
    implementation("org.apache.poi:poi-ooxml:5.5.1")
    // Both drivers ship: H2 is the file database a local run seeds into, Postgres is what a
    // deployed tenant is handed. Which one is used follows the URL, so neither is pinned here.
    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test> {
    useJUnitPlatform()
}
