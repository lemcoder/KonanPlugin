#ifndef CPPMATH_H
#define CPPMATH_H

/* A C API over C++ (cppmath.cpp): jextract binds C declarations. */
#ifdef __cplusplus
extern "C" {
#endif

double cpp_median(const int *values, int count);
const char *cpp_reverse(const char *text);

#ifdef __cplusplus
}
#endif

#endif
