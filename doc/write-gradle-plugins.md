The Android Gradle plugin (AGP) is the official build system for Android
applications. It includes support for compiling many different types of sources
and linking them together into an application that you can run on a physical
Android device or an emulator.

AGP contains extension points for plugins to control build inputs and extend its
functionality through new steps that can be integrated with standard build
tasks. Previous versions of AGP did not have official APIs clearly separated
from internal implementations. Starting in version 7.0, AGP has a set of
[official, stable APIs](https://developer.android.com/reference/tools/gradle-api) that you can rely
on.

## AGP API lifecycle

AGP follows the [Gradle feature lifecycle](https://docs.gradle.org/current/userguide/feature_lifecycle.html) to designate the state of its APIs:

- **Internal**: Not intended for public use
- **Incubating**: Available for public use but not final, which means that they may not be backward compatible in the final version
- **Public**: Available for public use and stable
- **Deprecated**: No longer supported, and replaced with new APIs

## Deprecation policy

AGP is evolving with the deprecation of old APIs and their
replacement with new, stable APIs and a new Domain Specific Language (DSL).
This evolution will span multiple AGP releases, and you can learn more about it
at the [AGP API/DSL migration timeline](https://developer.android.com/studio/releases/gradle-plugin-roadmap).

When AGP APIs are deprecated, for this migration or otherwise, they will
continue to be available in the current major release but will generate
warnings. Deprecated APIs will be fully removed from AGP in the subsequent major
release. For example, if an API is deprecated in AGP 7.0, it will be available
in that version and generate warnings. That API will no longer be available in
AGP 8.0.

To see examples of new APIs used in common build customizations, take a look
at the [Android Gradle plugin recipes](https://github.com/android/gradle-recipes).
They provide examples of common build customizations. You can also find more
details about the new APIs in our
[reference documentation](https://developer.android.com/reference/tools/gradle-api).

## Gradle build basics

This guide doesn't cover the entire Gradle build system. However, it does cover
the minimum necessary set of concepts to help you integrate with our APIs, and
links out to the main Gradle documentation for further reading.

We do assume basic knowledge about how Gradle works, including how to configure
projects, edit build files, apply plugins, and run tasks. To learn about the
basics of Gradle with respect to AGP, we recommend reviewing [Configure your
build](https://developer.android.com/studio/build). To learn about the general framework for customizing
Gradle plugins, see
[Developing Custom Gradle Plugins](https://docs.gradle.org/current/userguide/custom_plugins.html).

### Gradle lazy types glossary

Gradle offers a number of types that behave "lazily," or help defer heavy
computations or
[`Task`](https://docs.gradle.org/current/javadoc/org/gradle/api/Task.html)
creation to later phases of the build. These types are at the core of many
Gradle and AGP APIs. The following list includes the main Gradle types involved
in lazy execution, and their key methods.

[`Provider<T>`](https://docs.gradle.org/current/javadoc/org/gradle/api/provider/Provider.html)
:   Provides a value of type `T` (where "T" means any type), which can be read
during the execution phase using
[`get()`](https://docs.gradle.org/current/javadoc/org/gradle/api/provider/Provider.html#get--)
or transformed into a new `Provider<S>` (where "S" means some other type)
using the `map()`, `flatMap()`, and `zip()`methods. Note that `get()` should
never be called during the configuration phase.

    - [`map()`](https://docs.gradle.org/current/javadoc/org/gradle/api/provider/Prov%0Aider.html#map-org.gradle.api.Transformer-): Accepts a [lambda](https://kotlinlang.org/docs/lambdas.html#higher-%0Aorder-functions) and produces a `Provider` of type `S`, `Provider<S>`. The lambda argument to `map()` takes the value `T` and produces the value `S`. The lambda is not executed immediately; instead, its execution is deferred to the moment `get()` is called on the resulting `Provider<S>`, making the whole chain lazy.
    - [`flatMap()`](https://docs.gradle.org/current/javadoc/org/gradle/api/provider/Provider.html#flatMap-org.gradle.api.Transformer-): Also accepts a lambda and produces `Provider<S>`, but the lambda takes a value `T` and produces `Provider<S>` (instead of producing the value `S` directly). Use flatMap() when S cannot be determined at configuration time and you can obtain only `Provider<S>`. Practically speaking, if you used `map()` and ended up with a `Provider<Provider<S>>` result type, that probably means you should have used `flatMap()` instead.
    - [`zip()`](https://docs.gradle.org/current/javadoc/org/gradle/api/provider/Provider.html#zip-org.gradle.api.provider.Provider-java.util.function.BiFunction-): Lets you combine two `Provider` instances to produce a new `Provider`, with a value computed using a function that combines the values from the two input `Providers` instances.

[`Property<T>`](https://docs.gradle.org/current/javadoc/org/gradle/api/provider/Property.html)
:   Implements `Provider<T>`, so it also provides a value of type `T`. Unlike with
`Provider<T>`, which is read-only, you can also set a value for the
`Property<T>`. There are two ways to do so:

    - Set a value of type `T` directly when it's available, without the need for deferred computations.
    - Set another `Provider<T>` as the source of the value of the `Property<T>`. In this case, the value `T` is materialized only when `Property.get()` is called.

[`TaskProvider`](https://docs.gradle.org/current/javadoc/org/gradle/api/tasks/TaskProvider.html)
:   Implements `Provider<Task>`. To generate a `TaskProvider`, use
[`tasks.register()`](https://docs.gradle.org/current/javadoc/org/gradle/api/tasks/TaskContainer.html#register-java.lang.String-) and not [`tasks.create()`](https://docs.gradle.org/current/javadoc/org/gradle/api/tasks/TaskContainer.html#create-java.lang.String-), to ensure tasks are only instantiated
lazily when they're needed. You can use `flatMap()` to access the outputs of
a `Task` before the `Task` is created, which can be useful if you want to use
the outputs as inputs to other `Task` instances.

Providers and their transformation methods are essential for setting up inputs
and outputs of tasks in a lazy way, that is, without the need to
create all tasks up front and resolve the values.

Providers also carry task dependency information. When you create a `Provider` by
transforming a `Task` output, that `Task` becomes an implicit dependency of the
`Provider` and will be created and run whenever the value of the `Provider` is
resolved, such as when another `Task` requires it.

Here's an example of registering two tasks, `GitVersionTask` and
`ManifestProducerTask`, while deferring creation of the `Task` instances until
they are actually required. The `ManifestProducerTask` input value is set to a
`Provider` obtained from the output of `GitVersionTask`, so
`ManifestProducerTask` implicitly depends on `GitVersionTask`.

    // Register a task lazily to get its TaskProvider.
    val gitVersionProvider: TaskProvider =
        project.tasks.register("gitVersionProvider", GitVersionTask::class.java) {
            it.gitVersionOutputFile.set(
                File(project.buildDir, "intermediates/gitVersionProvider/output")
            )
        }

    ...

    /**
     * Register another task in the configuration block (also executed lazily,
     * only if the task is required).
     */
    val manifestProducer =
        project.tasks.register(variant.name + "ManifestProducer", ManifestProducerTask::class.java) {
            /**
             * Connect this task's input (gitInfoFile) to the output of
             * gitVersionProvider.
             */
            it.gitInfoFile.set(gitVersionProvider.flatMap(GitVersionTask::gitVersionOutputFile))
        }

These two tasks will only execute if they are explicitly requested. This can
happen as part of a Gradle invocation, for example, if you run `./gradlew
debugManifestProducer`, or if the output of `ManifestProducerTask` is connected
to some other task and its value becomes required.

While you will write custom tasks that consume inputs and/or produce outputs,
AGP doesn't offer public access to its own tasks directly. They are an
implementation detail subject to change from version to version. Instead, AGP
offers the Variant API and access to the output of its tasks, or *build
artifacts* , that you can read and transform. See
[Variant API, Artifacts, and Tasks](https://developer.android.com/build/extend-agp#variant-api-artifacts-tasks) in this
document for more information.

## Gradle build phases

Building a project is inherently a complicated and resource demanding process,
and there are various features such as task configuration avoidance, up-to-date
checks, and the configuration caching feature that help minimize the time spent
on reproducible or unnecessary computations.

To apply some of these optimizations, Gradle scripts and plugins must
obey strict rules during each of the distinct Gradle build phases:
initialization, configuration, and execution. In this guide, we will focus on
the configuration and execution phases. You can find more information about all
the phases in the [Gradle build lifecycle guide](https://docs.gradle.org/current/userguide/build_lifecycle.html).

### Configuration phase

During the configuration phase, the build scripts for all projects that are part
of the build are evaluated, the plugins are applied, and build dependencies are
resolved. This phase should be used to configure the build using DSL objects and
for registering tasks and their inputs lazily.

Because the configuration phase always runs, regardless of which task is
requested to run, it is especially important to keep it lean and restrict any
computations from depending on inputs other than the build scripts themselves.
That is, you shouldn't execute external programs or read from the network, or
perform long computations that can be deferred to the execution phase as proper
`Task` instances.

> [!NOTE]
> **Note:** With the upcoming configuration caching feature the result of this phase will be cached for subsequent runs, but only if [special care](https://docs.gradle.org/current/userguide/configuration_cache.html#config_cache:requirements) is taken to let the build system know about any dynamic inputs such as file reads or access to environment variables.

### Execution phase

In the execution phase, the requested tasks and their dependent tasks are
executed. Specifically, the `Task` class method(s) marked with `@TaskAction` are
executed. During task execution, you are allowed to read from inputs (such as
files) and resolve lazy providers by calling `Provider<T>.get()`. Resolving lazy
providers this way kicks off a sequence of `map()` or `flatMap()` calls that follow
the task dependency information contained within the provider. Tasks are run
lazily to materialize the required values.

## Variant API, Artifacts, and Tasks

The Variant API is an extension mechanism in the Android Gradle plugin that lets
you manipulate the various options, normally set using the [DSL](https://developer.android.com/reference/tools/gradle-api/7.0/com/android/build/api/dsl/ApplicationExtension) in build
configuration files, that influence the Android build. The Variant API also
gives you access to intermediate and final artifacts that are created by the
build, such as class files, the merged manifest, or APK/AAB files.

### Android build flow and extension points

When interacting with AGP, use specially made extension points instead
of registering the typical Gradle lifecycle callbacks (such as [`afterEvaluate()`](https://docs.gradle.org/current/javadoc/org/gradle/api/Project.html#afterEvaluate-org.gradle.api.Action-)) or
setting up explicit `Task` dependencies. Tasks created by AGP are considered
implementation details and are not exposed as a public API. You must avoid
trying to get instances of the `Task` objects or guessing the `Task` names and
adding callbacks or dependencies to those `Task` objects directly.

AGP completes the following steps to create and execute its `Task` instances,
which in turn produce the build artifacts. The main steps involved in
[`Variant`](https://developer.android.com/reference/tools/gradle-api/7.0/com/android/build/api/variant/Variant)
object creation are followed by callbacks that let you make changes to certain
objects created as part of a build. It's important to note that all of the
callbacks happen during the [configuration phase](https://developer.android.com/build/extend-agp#configuration-phase)
(described on this page) and must run fast, deferring any complicated work
to proper `Task` instances during the execution phase instead.

1. **DSL parsing** : This is when build scripts are evaluated, and when the various properties of the Android DSL objects from the `android` block are created and set. The Variant API callbacks described in the following sections are also registered during this phase.
2. [`finalizeDsl()`](https://developer.android.com/reference/tools/gradle-api/7.1/com/android/build/api/extension/AndroidComponentsExtension#finalizedsl):
   Callback that lets you change DSL objects before they are
   locked for component (variant) creation. `VariantBuilder` objects are created
   based on data contained in the DSL objects.

3. **DSL locking**: DSL is now locked and changes are no longer possible.

4. [`beforeVariants()`](https://developer.android.com/reference/tools/gradle-api/7.1/com/android/build/api/extension/AndroidComponentsExtension#beforevariant%0As): This callback can influence which components are created,
   and some of their properties, through [`VariantBuilder`](https://developer.android.com/reference/tools/gradle-api/7.0/com/android/build/api/variant/VariantBuilder). It still allows
   modifications to the build flow and the artifacts that are produced.

5. **Variant creation**: The list of components and artifacts that will be
   created is now finalized and cannot be changed.

6. [`onVariants()`](https://developer.android.com/reference/tools/gradle-api/4.2/com/android/build/api/extension/AndroidComponentsExtension#onvariants):
   In this callback, you get access to the created `Variant`
   objects and you can set values or providers for the `Property` values they
   contain, to be computed lazily.

7. **Variant locking**: Variant objects are now locked and changes are no longer
   possible.

8. **Tasks created** : `Variant` objects and their `Property` values are used to
   create the `Task` instances that are necessary to perform the build.

AGP introduces an
[`AndroidComponentsExtension`](https://developer.android.com/reference/tools/gradle-api/7.0/com/android/build/api/extension/AndroidComponentsExtension) that lets
you register callbacks for `finalizeDsl()`, `beforeVariants()` and `onVariants()`.
The extension is available in build scripts through the `androidComponents` block:

    // This is used only for configuring the Android build through DSL.
    android { ... }

    // The androidComponents block is separate from the DSL.
    androidComponents {
       finalizeDsl { extension ->
          ...
       }
    }

However, our recommendation is to keep build scripts only for declarative
configuration using the android block's DSL and
[move any custom imperative logic to `buildSrc`](https://docs.gradle.org/current/userguide/organizing_gradle_projects.html#sec:build_sources) or external plugins. You can also take a look at the [`buildSrc`
samples](https://github.com/android/gradle-recipes/tree/agp-7.0/BuildSrc/) in our Gradle recipes GitHub repository to learn how to create a plugin in your project. Here is an example of registering the callbacks from plugin code:

    abstract class ExamplePlugin: Plugin<Project> {

        override fun apply(project: Project) {
            val androidComponents = project.extensions.getByType(AndroidComponentsExtension::class.java)
            androidComponents.finalizeDsl { extension ->
                ...
            }
        }
    }

Let's take a closer look at the available callbacks and the type of use cases
that your plugin can support in each of them:

#### `finalizeDsl(callback: (DslExtensionT) -> Unit)`

In this callback, you are able to access and modify the DSL objects that were
created by parsing the information from the `android` block in the build files.
These DSL objects will be used to initialize and configure variants in later
phases of the build. For example, you can programmatically create new
configurations or override properties---but keep in mind that all values must be
resolved at configuration time, so they must not rely on any external inputs.
After this callback finishes executing, the DSL objects are no longer useful and
you should no longer hold references to them or modify their values.

    abstract class ExamplePlugin: Plugin<Project> {

        override fun apply(project: Project) {
            val androidComponents = project.extensions.getByType(AndroidComponentsExtension::class.java)
            androidComponents.finalizeDsl { extension ->
                extension.buildTypes.create("extra").let {
                    it.isJniDebuggable = true
                }
            }
        }
    }

#### `beforeVariants()`

At this stage of the build, you get access to `VariantBuilder` objects, which
determine the variants that will be created and their properties. For example,
you can programmatically disable certain variants, their tests, or change a
property's value (for example, `minSdk`) only for a chosen variant. Similar to
`finalizeDsl()`, all of the values you provide must be resolved at configuration
time and not depend on external inputs. The `VariantBuilder` objects must not be
modified once execution of the `beforeVariants()` callback finishes.

    androidComponents {
        beforeVariants { variantBuilder ->
            variantBuilder.minSdk = 23
        }
    }

The `beforeVariants()` callback optionally takes a [`VariantSelector`](https://developer.android.com/reference/tools/gradle-api/7.1/com/android/build/api/extension/VariantSelector), which you can
obtain through the [`selector()`](https://developer.android.com/reference/tools/gradle-api/7.1/com/android/build/api/extension/AndroidComponentsExtension#selector()) method on the [`androidComponentsExtension`](https://developer.android.com/reference/tools/gradle-api/7.1/com/android/build/api/extension/AndroidComponentsExtension). You can
use it to filter components participating in the callback invocation based on
their name, build type, or product flavor.

    androidComponents {
        beforeVariants(selector().withName("adfree")) { variantBuilder ->
            variantBuilder.minSdk = 23
        }
    }

#### `onVariants()`

By the time `onVariants()` is called, all the artifacts that will be created by
AGP are already decided so you can no longer disable them. You can, however,
modify some of the values used for the tasks by setting them for
`Property` attributes in the `Variant` objects. Because the `Property` values will
only be resolved when AGP's tasks are executed, you can safely wire them up to
providers from your own custom tasks that will perform any required
computations, including reading from external inputs such as files or the network.

    // onVariants also supports VariantSelectors:
    onVariants(selector().withBuildType("release")) { variant ->
        // Gather the output when we are in single mode (no multi-apk).
        val mainOutput = variant.outputs.single { it.outputType == OutputType.SINGLE }

        // Create version code generating task
        val versionCodeTask = project.tasks.register("computeVersionCodeFor${variant.name}", VersionCodeTask::class.java) {
            it.outputFile.set(project.layout.buildDirectory.file("${variant.name}/versionCode.txt"))
        }
        /**
         * Wire version code from the task output.
         * map() will create a lazy provider that:
         * 1. Runs just before the consumer(s), ensuring that the producer
         * (VersionCodeTask) has run and therefore the file is created.
         * 2. Contains task dependency information so that the consumer(s) run after
         * the producer.
         */
        mainOutput.versionCode.set(versionCodeTask.map { it.outputFile.get().asFile.readText().toInt() })
    }

> [!NOTE]
> **Note:** When you generate files per variant make sure to add `${variant.name}/` to all filenames to prevent them from being overwritten when building multiple variants.

### Contribute generated sources to the build

Your plugin can contribute a few types of generated sources, such as:

- Application code in the `java` directory
- [Android resources](https://developer.android.com/guide/topics/resources/available-resources) in the `res` directory
- [Java resources](https://docs.gradle.org/current/userguide/java_plugin.html#sec:java_project_layout) in the `resources` directory
- [Android assets](https://developer.android.com/reference/android/content/res/AssetManager) in the `assets` directory

For the full list of sources you can add, see the
[Sources API](https://developer.android.com/reference/tools/gradle-api/7.4/com/android/build/api/variant/Sources).

This code snippet shows how to add a custom source folder called
`${variant.name}` to the Java source set using the `addStaticSourceDirectory()`
function. The Android toolchain then processes this folder.

    onVariants { variant ->
        variant.sources.java?.let { java ->
            java.addStaticSourceDirectory("custom/src/kotlin/${variant.name}")
        }
    }

See the [addJavaSource recipe](https://github.com/android/gradle-recipes/tree/agp-8.0/Kotlin/addJavaSource)
for more details.

This code snippet shows how to add a directory with Android resources
generated from a custom task to the `res` source set. The process is similar for other
source types.

    onVariants(selector().withBuildType("release")) { variant ->
        // Step 1. Register the task.
        val resCreationTask =
           project.tasks.register<ResCreatorTask>("create${variant.name}Res")

        // Step 2. Register the task output to the variant-generated source directory.
        variant.sources.res?.addGeneratedSourceDirectory(
           resCreationTask,
           ResCreatorTask::outputDirectory)
        }

    ...

    // Step 3. Define the task.
    abstract class ResCreatorTask: DefaultTask() {
       @get:OutputFiles
       abstract val outputDirectory: DirectoryProperty

       @TaskAction
       fun taskAction() {
          // Step 4. Generate your resources.
          ...
       }
    }

See the [addCustomAsset recipe](https://github.com/android/gradle-recipes/tree/agp-8.0/Kotlin/addCustomAsset)
for more details.

### Access and modify artifacts

In addition to letting you modify simple properties on the `Variant` objects, AGP
also contains an extension mechanism that allows you to read or transform
intermediate and final artifacts produced during the build. For example, you can
read the final, merged `AndroidManifest.xml` file contents in a custom `Task` to
analyze it, or you can replace its content entirely with that of a manifest file
generated by your custom `Task`.

You can find the list of artifacts currently supported in the reference
documentation for the [`Artifact`](https://developer.android.com/reference/tools/gradle-api/7.0/com/android/build/api/artifact/Artifact) class. Every artifact type has certain properties that are useful to know:

#### Cardinality

The cardinality of an [`Artifact`](https://developer.android.com/reference/tools/gradle-api/7.1/com/android/build/api/artifact/Artifact) represents its number of [`FileSystemLocation`](https://docs.gradle.org/current/javadoc/org/gradle/api/file/FileSystemLocation.html)
instances, or the number of files or directories of the artifact type. You can
get information about the cardinality of an artifact by checking its parent
class: Artifacts with a single `FileSystemLocation` will be a subclass of
[`Artifact.Single`](https://developer.android.com/reference/tools/gradle-api/7.1/com/android/build/api/artifact/Artifact.Single); artifacts with multiple `FileSystemLocation` instances will
be a subclass of [`Artifact.Multiple`](https://developer.android.com/reference/tools/gradle-api/7.1/com/android/build/api/artifact/Artifact.Multiple).

#### `FileSystemLocation` type

You can check if an `Artifact` represents files or directories by looking at its
parameterized `FileSystemLocation` type, which can be either a [`RegularFile`](https://docs.gradle.org/current/javadoc/org/gradle/api/file/RegularFile.html) or a
[`Directory`](https://docs.gradle.org/current/javadoc/org/gradle/api/file/Directory.html).

#### Supported operations

Every `Artifact` class can implement any of the following interfaces to indicate
which operations it supports:

- [`Transformable`](https://developer.android.com/reference/tools/gradle-api/7.1/com/android/build/api/artifact/Artifact.Transformable): Allows an `Artifact` to be used as an input to a `Task` that performs arbitrary transformations on it and outputs a new version of the `Artifact`.
- [`Appendable`](https://developer.android.com/reference/tools/gradle-api/7.1/com/android/build/api/artifact/Artifact.Appendable): Applies only to artifacts that are subclasses of `Artifact.Multiple`. It means that the `Artifact` can be appended to, that is, a custom `Task` can create new instances of this `Artifact` type which will be added to the existing list.
- [`Replaceable`](https://developer.android.com/reference/tools/gradle-api/4.1/com/android/build/api/artifact/Artifact.Replaceable): Applies only to artifacts that are subclasses of `Artifact.Single`. A replaceable `Artifact` can be replaced by an entirely new instance, produced as an output of a `Task`.

In addition to the three artifact-modifying operations, every artifact supports
a [`get()`](https://developer.android.com/reference/tools/gradle-api/7.0/com/android/build/api/artifact/Artifacts#get)
(or [`getAll()`](https://developer.android.com/reference/tools/gradle-api/7.0/com/android/build/api/artifact/Artifacts#getall))
operation, which returns a `Provider` with the final version of the artifact
(after all operations on it are finished).

Multiple plugins can add any number of operations on artifacts into the pipeline
from the `onVariants()` callback, and AGP will ensure they are chained properly so
that all tasks run at the right time and artifacts are correctly produced and
updated. This means that when an operation changes any outputs by appending,
replacing, or transforming them, the next operation will see the updated version
of these artifacts as inputs, and so on.

The entry point into registering operations is the `Artifacts` class.
The following code snippet shows how you can get access to an instance of
`Artifacts` from a property on the `Variant` object in the `onVariants()`
callback.

You can then pass in your custom `TaskProvider` to get a
[`TaskBasedOperation`](https://developer.android.com/reference/tools/gradle-%0Aapi/7.1/com/android/build/api/artifact/TaskBasedOperation)
object (1), and use it to connect its inputs and outputs using one of the
`wiredWith*` methods (2).

The exact method you need to choose depends on the cardinality and
`FileSystemLocation` type implemented by the `Artifact` that you want to transform.

And finally, you pass in the `Artifact` type to a method representing the chosen
operation on the `*OperationRequest` object that you get in return, for example,
[`toAppendTo()`](https://developer.android.com/reference/tools/gradle-%0Aapi/7.1/com/android/build/api/artifact/OutOperationRequest#toappendto),
[`toTransform()`](https://developer.android.com/reference/tools/gradle-%0Aapi/7.1/com/android/build/api/artifact/InAndOutFileOperationRequest#totransform)
, or [`toCreate()`](https://developer.android.com/reference/tools/gradle-%0Aapi/7.1/com/android/build/api/artifact/OutOperationRequest#tocreate) (3).

    androidComponents.onVariants { variant ->
        val manifestUpdater = // Custom task that will be used for the transform.
                project.tasks.register(variant.name + "ManifestUpdater", ManifestTransformerTask::class.java) {
                    it.gitInfoFile.set(gitVersionProvider.flatMap(GitVersionTask::gitVersionOutputFile))
                }
        // (1) Register the TaskProvider w.
        val variant.artifacts.use(manifestUpdater)
             // (2) Connect the input and output files.
            .wiredWithFiles(
                ManifestTransformerTask::mergedManifest,
                ManifestTransformerTask::updatedManifest)
            // (3) Indicate the artifact and operation type.
            .toTransform(SingleArtifact.MERGED_MANIFEST)
    }

In this example, `MERGED_MANIFEST` is a `SingleArtifact`, and it is a
`RegularFile`. Because of that, we need to use the [`wiredWithFiles`](https://developer.android.com/reference/tools/gradle-api/7.0/com/android/build/api/artifact/TaskBasedOperation#wiredWithFiles(kotlin.Function1,%20kotlin.Function1)) method, which
accepts a single `RegularFileProperty` reference for the input, and a single
`RegularFileProperty` for the output. There are other `wiredWith*` methods on
the `TaskBasedOperation` class that will work for other combinations of `Artifact`
cardinality and `FileSystemLocation` types.

To learn more about extending AGP, we recommend reading the following sections
from the Gradle build system manual:

- [Developing Custom Gradle Plugins](https://docs.gradle.org/current/userguide/custom_plugins.html)
- [Implementing Gradle plugins](https://docs.gradle.org/current/userguide/implementing_gradle_plugins.html)
- [Developing Custom Gradle Task Types](https://docs.gradle.org/current/userguide/custom_tasks.html)
- [Lazy Configuration](https://docs.gradle.org/current/userguide/lazy_configuration.html)
- [Task Configuration Avoidance](https://docs.gradle.org/current/userguide/task_configuration_avoidance.html)