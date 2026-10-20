#ifndef RUSTMATH_H
#define RUSTMATH_H

#include <stdint.h>

/* Implemented in Rust (src/lib.rs); this header is what cinterop and the JNI generator read. */
int32_t rust_fib(int32_t n);
uint32_t rust_count_vowels(const char *text);

#endif
