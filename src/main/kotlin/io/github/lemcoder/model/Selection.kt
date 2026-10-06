package io.github.lemcoder.model

/**
 * The one choice [selected] holds, else [default]: a DSL owner such as a library or a binding's `jni`
 * picks how it is built by calling one block, and calling two is an error naming [owner].
 */
internal fun <T : Any> selectOne(owner: String, selected: Set<T>, default: T): T {
    check(selected.size <= 1) { "$owner selects ${selected.joinToString(" and ")}; it is built one way." }
    return selected.singleOrNull() ?: default
}
