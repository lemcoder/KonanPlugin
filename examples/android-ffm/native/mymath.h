#ifndef MYMATH_H
#define MYMATH_H

int my_add(int a, int b);
double my_scale(double x);

/* Marshalled shapes: strings both ways, a read-only buffer, and one the callee writes into. */
int my_length(const char* text);
const char* my_greeting(void);
int my_sum(const int* values, int count);
void my_fill(int* out, int count, int value);

#endif
