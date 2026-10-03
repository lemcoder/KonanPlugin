// Covers the shapes the index file has to carry: self-referential structs, unions, bit-fields,
// anonymous members, enums, typedef chains, function pointers, arrays and macro constants.
#include <stddef.h>
#include <stdint.h>

#define SAMPLE_VERSION 3
#define SAMPLE_PI 3.14159
#define SAMPLE_NAME "sample"
#define SAMPLE_MASK (1u << 4)

typedef struct node node_t;
struct node {
    int32_t value;
    node_t *next;       /* back to itself through a typedef */
    struct node *prev;  /* and directly */
};

typedef union {
    uint32_t as_int;
    float as_float;
} bits_t;

struct flags {
    unsigned ready : 1;
    unsigned mode : 3;
    struct { int x, y; } point;
    union { int i; char c; };
};

enum color { RED, GREEN = 5, BLUE };
typedef enum { SMALL = 1, LARGE = 100 } size_class;

typedef int (*compare_fn)(const void *, const void *);

struct buffer {
    size_t length;
    uint8_t data[16];
};

struct packet {
    int count;
    int payload[];
};

struct opaque;

extern const int sample_global;

int sample_sum(const int *values, size_t count);
const char *sample_name(void);
void sample_sort(void *base, size_t n, compare_fn cmp);
double sample_scale(node_t *node, double factor);
struct buffer sample_buffer(struct opaque *handle, enum color c, size_class s);
int sample_log(const char *format, ...);
size_t sample_packet_size(const struct packet *p);
bits_t sample_bits(struct flags f);
