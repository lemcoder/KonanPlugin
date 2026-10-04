#include <string.h>
#include "mymath.h"

int my_add(int a, int b) { return a + b; }
double my_scale(double x) { return x * 10.0; }

int my_length(const char* text) { return text ? (int)strlen(text) : -1; }
const char* my_greeting(void) { return "hello from C"; }

int my_sum(const int* values, int count) {
    int total = 0;
    for (int i = 0; i < count; i++) total += values[i];
    return total;
}

void my_fill(int* out, int count, int value) {
    for (int i = 0; i < count; i++) out[i] = value;
}
