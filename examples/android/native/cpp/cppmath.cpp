#include "cppmath.h"

#include <algorithm>
#include <string>
#include <vector>

double cpp_median(const int *values, int count) {
    if (values == nullptr || count <= 0) return 0.0;
    std::vector<int> sorted(values, values + count);
    std::sort(sorted.begin(), sorted.end());
    return count % 2 ? sorted[count / 2] : (sorted[count / 2 - 1] + sorted[count / 2]) / 2.0;
}

const char *cpp_reverse(const char *text) {
    // Valid until the next call on this thread.
    thread_local std::string reversed;
    reversed = text ? text : "";
    std::reverse(reversed.begin(), reversed.end());
    return reversed.c_str();
}
