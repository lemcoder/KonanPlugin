package io.github.lemcoder

sealed class LibraryType {
    object STATIC : LibraryType()
    object SHARED : LibraryType()
}