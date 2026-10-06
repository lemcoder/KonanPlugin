package io.github.lemcoder.model

/**
 * Whether generated bindings are visible outside the module that declares them. One type for the DSL and
 * the generator: it lives with the exporter protocol, which both the plugin and the exporter compile.
 */
enum class BindingVisibility { INTERNAL, PUBLIC }
