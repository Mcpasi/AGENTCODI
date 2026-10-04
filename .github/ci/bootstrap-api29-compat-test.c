/* Allocation/overflow checks run with the actual ARM64/Bionic loader. */
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
extern void* reallocarray(void*, size_t, size_t);

int main(void) {
    unsigned char* memory = reallocarray(NULL, 4, 8);
    if (!memory) return 1;
    memset(memory, 0x5a, 32);
    errno = 0;
    if (reallocarray(memory, SIZE_MAX, 2) != NULL || errno != ENOMEM) return 2;
    for (size_t i = 0; i < 32; ++i) if (memory[i] != 0x5a) return 3;
    unsigned char* grown = reallocarray(memory, 8, 8);
    if (!grown) return 4;
    for (size_t i = 0; i < 32; ++i) if (grown[i] != 0x5a) return 5;
    free(grown);
    puts("CI reallocarray allocation, resize and overflow checks passed");
    return 0;
}
