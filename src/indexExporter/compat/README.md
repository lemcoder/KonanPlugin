# Index exporter compatibility facets

cinterop's API moves between Kotlin releases. The exporter is compiled once per supported Kotlin/Native
version (`kotlinNativeVersions` in `gradle.properties`), and each compilation adds one directory per
facet: the variant whose threshold the version reaches. Each directory defines the same declarations, so
the shared sources do not know which one they got.

| Facet       | Variant (from)                  | What changed in cinterop |
|-------------|---------------------------------|--------------------------|
| `anonymous` | `spelling` (2.0.0), none (2.0.20) | `StructDecl.isAnonymous`, `EnumDef.isAnonymous` arrive; before, clang's spelling says it |
| `access`    | `none` (2.0.0), `direct` (2.3.20) | `FunctionDecl.directAccess`, `GlobalDecl.directAccess` arrive |
| `frontend`  | `2.0.0`, `2.4.20`                 | `prepareTool(konanHome =)`, `-Xkonan-home`, `buildNativeIndex(macroNamesCollectingMode =)` |

The thresholds live in `buildSrc/src/main/kotlin/native-index.gradle.kts`. A new Kotlin release that
breaks the exporter's compilation gets a new variant directory and a threshold, not an `if` in the
shared sources.
