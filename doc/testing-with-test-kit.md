Testing Build Logic with TestKit

On this Page
Using TestKit
Functional testing with the Gradle runner
Getting the plugin-under-test into the test build
Automatic injection with the Java Gradle Plugin Development plugin
Controlling the build environment
Setting the Gradle version used to test
Debugging build logic
Testing with the Build Cache
The Gradle TestKit helps you functionally test Gradle plugins and build logic by executing real builds in an isolated temp project and asserting on their results. You’ll typically pair TestKit with a test framework (JUnit or Spock) and a few small fixtures to generate test projects on the fly. Over time, TestKit has focused on functional/black-box testing, and that remains its sweet spot.

Using TestKit

Add TestKit to your test dependencies:

KotlinGroovy
build.gradle.kts
dependencies {
testImplementation(gradleTestKit())
}

The gradleTestKit() encompasses the classes of the TestKit, as well as the Gradle Tooling API client. It does not include a version of JUnit, TestNG, or any other test execution framework. Such a dependency must be explicitly declared.

For JUnit 5:

KotlinGroovy
build.gradle.kts
dependencies {
testImplementation("org.junit.jupiter:junit-jupiter:5.7.1")
testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
useJUnitPlatform()
}

Functional testing with the Gradle runner

A modern way to organize plugin tests is to add a dedicated functional test suite using the JVM Test Suite plugin (added transitively by java-gradle-plugin). This cleanly separates unit and functional tests and wires TestKit for you.

The GradleRunner allows you to programmatically execute Gradle builds and inspect their results.

A test can create a minimal or contrived build (either programmatically or from a template) that exercises the logic under test. The functional test then runs that build, potentially using different tasks, arguments, or Gradle versions, and verifies correctness by asserting any combination of the following:

The build output

The build’s logging

The tasks that executed and their outcomes (e.g., FAILED, UP-TO-DATE, FROM-CACHE)

After creating and configuring a runner instance, execute the build using GradleRunner.build() or GradleRunner.buildAndFail(), depending on whether the build is expected to succeed or fail.

The following demonstrates the usage of the Gradle runner in a Java JUnit test:

BuildLogicFunctionalTest.java
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BuildLogicFunctionalTest {

    @TempDir File testProjectDir;
    private File settingsFile;
    private File buildFile;

    @BeforeEach
    public void setup() {
        settingsFile = new File(testProjectDir, "settings.gradle");
        buildFile = new File(testProjectDir, "build.gradle");
    }

    @Test
    public void testHelloWorldTask() throws IOException {
        writeFile(settingsFile, "rootProject.name = 'hello-world'");
        String buildFileContent = "task helloWorld {" +
                                  "    doLast {" +
                                  "        println 'Hello world!'" +
                                  "    }" +
                                  "}";
        writeFile(buildFile, buildFileContent);

        BuildResult result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withArguments("helloWorld")
            .build();

        assertTrue(result.getOutput().contains("Hello world!"));
        assertEquals(SUCCESS, result.task(":helloWorld").getOutcome());
    }

    private void writeFile(File destination, String content) throws IOException {
        BufferedWriter output = null;
        try {
            output = new BufferedWriter(new FileWriter(destination));
            output.write(content);
        } finally {
            if (output != null) {
                output.close();
            }
        }
    }
}

Any test framework can be used.

Because Gradle build scripts are often written in Groovy, many users find it productive to write functional tests in Groovy as well. The Spock framework provides expressive assertions, data-driven tests, and powerful fixtures that work well with TestKit. Similarly, Kotlin users often choose either JUnit Jupiter or Kotest for a more idiomatic Kotlin testing experience.

The following demonstrates the usage of the Gradle runner in a Groovy Spock test:

BuildLogicFunctionalTest.groovy
import org.gradle.testkit.runner.GradleRunner
import static org.gradle.testkit.runner.TaskOutcome.*
import spock.lang.TempDir
import spock.lang.Specification

class BuildLogicFunctionalTest extends Specification {
@TempDir File testProjectDir
File settingsFile
File buildFile

    def setup() {
        settingsFile = new File(testProjectDir, 'settings.gradle')
        buildFile = new File(testProjectDir, 'build.gradle')
    }

    def "hello world task prints hello world"() {
        given:
        settingsFile << "rootProject.name = 'hello-world'"
        buildFile << """
            task helloWorld {
                doLast {
                    println 'Hello world!'
                }
            }
        """

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withArguments('helloWorld')
            .build()

        then:
        result.output.contains('Hello world!')
        result.task(":helloWorld").outcome == SUCCESS
    }
}

When implementing more complex build logic (such as custom plugins or task types), you can package that logic into external classes and test them in isolation using "normal" unit tests. Functional tests using TestKit then validate that the plugin behaves correctly when applied to a real Gradle build.

Getting the plugin-under-test into the test build

The GradleRunner uses the Tooling API to execute builds. This means that builds run in a separate process from your tests. As a result:

The test build does not share the same classpath or classloaders as the test process.

The code under test (for example, your plugin implementation) is not automatically visible to the build executed by GradleRunner.

GradleRunner supports the same range of Gradle versions as the Tooling API. The supported versions are defined in the compatibility matrix.

Builds with older Gradle versions may still work, but there are no guarantees.
To make your plugin (or other build logic under test) available to the test build, Gradle provides a conventional mechanism to inject the plugin-under-test onto the classpath used by GradleRunner.

Automatic injection with the Java Gradle Plugin Development plugin

The Java Gradle Plugin Development plugin provides built-in integration with TestKit. When applied to a project, it automatically:

adds the gradleTestKit() dependency to the appropriate test configuration, and

generates the plugin-under-test classpath and injects it into any GradleRunner instance created by your tests via GradleRunner.withPluginClasspath().

This allows functional tests to execute a “real” Gradle build that can apply and exercise your plugin without requiring any manual classpath wiring.

Automatic classpath injection only works when the plugin-under-test is applied in the test build using the plugins {} DSL.
By default, the Java Gradle Plugin Development plugin uses the following conventions:

Code under test is taken from: sourceSets.main

The generated plugin classpath metadata is produced from: sourceSets.test

These conventions can be customized using the GradlePluginDevelopmentExtension.

The following sample shows the default setup for automatic plugin classpath injection:

KotlinGroovy
build.gradle.kts
plugins {
groovy
`java-gradle-plugin`
}

dependencies {
testImplementation("org.spockframework:spock-core:2.4-groovy-4.0") {
exclude(group = "org.apache.groovy")
}
testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

The following example automatically injects the code under test classes into test builds:

src/test/groovy/org/gradle/sample/BuildLogicFunctionalTest.groovy
def "hello world task prints hello world"() {
given:
settingsFile << "rootProject.name = 'hello-world'"
buildFile << """
plugins {
id 'org.gradle.sample.helloworld'
}
"""

        when:
        def result = GradleRunner.create()
            .withProjectDir(testProjectDir)
            .withArguments('helloWorld')
            .withPluginClasspath()
            .build()

        then:
        result.output.contains('Hello world!')
        result.task(":helloWorld").outcome == SUCCESS
    }

If your project uses a custom test source set (for example, a dedicated functional test suite), you can reconfigure the plugin development extension to generate the plugin classpath metadata from that source set instead.

A dedicated DSL for modeling such test suites is provided by the incubating JVM Test Suite plugin.
KotlinGroovy
build.gradle.kts
plugins {
groovy
`java-gradle-plugin`
}

val functionalTest = sourceSets.create("functionalTest")
val functionalTestTask = tasks.register<Test>("functionalTest") {
group = "verification"
testClassesDirs = functionalTest.output.classesDirs
classpath = functionalTest.runtimeClasspath
useJUnitPlatform()
}

tasks.check {
dependsOn(functionalTestTask)
}

gradlePlugin {
testSourceSets(functionalTest)
}

dependencies {
"functionalTestImplementation"("org.spockframework:spock-core:2.4-groovy-4.0") {
exclude(group = "org.apache.groovy")
}
"functionalTestRuntimeOnly"("org.junit.platform:junit-platform-launcher")
}

Controlling the build environment

GradleRunner executes test builds in an isolated environment. Each build runs in its own dedicated working directory inside the JVM’s temporary directory (the location defined by the java.io.tmpdir system property, typically /tmp).

Because the test build runs in this isolated directory:

Configuration from the default Gradle User Home (for example, ~/.gradle/gradle.properties) is not used.

The test build does not inherit settings, caches, or environment customizations from the machine running the tests.

The TestKit does not currently expose fine-grained control over all environmental aspects—for example, selecting a JDK for the executed build. Future versions of TestKit may offer more explicit configuration options.

TestKit uses dedicated Gradle daemon processes for the builds it runs. These daemons are distinct from any daemons used by "normal" Gradle invocations and are automatically shut down after the tests complete.

By default, the working directories created for each test build are not deleted. To keep the filesystem clean or to store TestKit state in a location managed by your build (such as a project’s build/ directory), you can specify a custom TestKit directory using one of the following:

The org.gradle.testkit.dir system property

GradleRunner.withTestKitDir(File testKitDir)

Setting the Gradle version used to test

The Gradle runner requires a Gradle distribution in order to execute the build. The TestKit does not depend on all of Gradle’s implementation.

By default, the runner will attempt to find a Gradle distribution based on where the GradleRunner class was loaded from. That is, it is expected that the class was loaded from a Gradle distribution, as is the case when using the gradleTestKit() dependency declaration.

When using the runner as part of tests being executed by Gradle (e.g. executing the test task of a plugin project), the same distribution used to execute the tests will be used by the runner. When using the runner as part of tests being executed by an IDE, the same Gradle distribution that was used when importing the project will be used. This means that the plugin will be effectively tested with the same version of Gradle that it is being built with.

Alternatively, a different and specific version of Gradle to use can be specified by the following GradleRunner methods:

GradleRunner.withGradleVersion(java.lang.String)

GradleRunner.withGradleInstallation(java.io.File)

GradleRunner.withGradleDistribution(java.net.URI)

This can potentially be used to test build logic across Gradle versions. The following demonstrates a cross-version compatibility test written as a Groovy Spock test:

BuildLogicFunctionalTest.groovy
import org.gradle.testkit.runner.GradleRunner
import static org.gradle.testkit.runner.TaskOutcome.*
import spock.lang.TempDir
import spock.lang.Specification

class BuildLogicFunctionalTest extends Specification {
@TempDir File testProjectDir
File settingsFile
File buildFile

    def setup() {
        settingsFile = new File(testProjectDir, 'settings.gradle')
        buildFile = new File(testProjectDir, 'build.gradle')
    }

    def "can execute hello world task with Gradle version #gradleVersion"() {
        given:
        buildFile << """
            task helloWorld {
                doLast {
                    logger.quiet 'Hello world!'
                }
            }
        """
        settingsFile << ""

        when:
        def result = GradleRunner.create()
            .withGradleVersion(gradleVersion)
            .withProjectDir(testProjectDir)
            .withArguments('helloWorld')
            .build()

        then:
        result.output.contains('Hello world!')
        result.task(":helloWorld").outcome == SUCCESS

        where:
        gradleVersion << ['5.0', '6.0.1']
    }
}

Debugging build logic

The runner uses the Tooling API to execute builds. An implication of this is that the builds are executed in a separate process (i.e., not the same process executing the tests). Therefore, executing your tests in debug mode does not allow you to debug your build logic as you may expect. Any breakpoints set in your IDE will be not be tripped by the code being exercised by the test build.

The TestKit provides two different ways to enable the debug mode:

Setting “org.gradle.testkit.debug” system property to true for the JVM using the GradleRunner (i.e., not the build being executed with the runner);

Calling the GradleRunner.withDebug(boolean) method.

The system property approach can be used when it is desirable to enable debugging support without making an ad hoc change to the runner configuration. Most IDEs offer the capability to set JVM system properties for test execution, and this feature can be used to set the desired system property.

Testing with the Build Cache

To enable the Build Cache in your tests, you can pass the --build-cache argument to GradleRunner or use one of the other methods described in Enable the Build Cache. You can then check for the task outcome TaskOutcome.FROM_CACHE when your plugin’s custom task is cached.

BuildLogicFunctionalTest.groovy
def "cacheableTask is loaded from cache"() {
given:
buildFile << """
plugins {
id 'org.gradle.sample.helloworld'
}
"""

        when:
        def result = runner()
            .withArguments( '--build-cache', 'cacheableTask')
            .build()

        then:
        result.task(":cacheableTask").outcome == SUCCESS

        when:
        new File(testProjectDir, 'build').deleteDir()
        result = runner()
            .withArguments( '--build-cache', 'cacheableTask')
            .build()

        then:
        result.task(":cacheableTask").outcome == FROM_CACHE
    }

Note that TestKit re-uses a Gradle User Home between tests (see GradleRunner.withTestKitDir(java.io.File)), which contains the default location for the local Build Cache. For testing with the build cache, the Build Cache directory should be cleaned between tests. The easiest way to accomplish this is to configure the local Build Cache to use a temporary directory:

BuildLogicFunctionalTest.groovy
@TempDir File testProjectDir
File buildFile
File localBuildCacheDirectory

    def setup() {
        localBuildCacheDirectory = new File(testProjectDir, 'local-cache')
        buildFile = new File(testProjectDir,'settings.gradle') << """
            buildCache {
                local {
                    directory = '${localBuildCacheDirectory.toURI()}'
                }
            }
        """
        buildFile = new File(testProjectDir,'build.gradle')
    }