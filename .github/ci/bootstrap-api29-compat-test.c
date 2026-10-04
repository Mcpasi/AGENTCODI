/* Allocation/overflow checks run with the actual ARM64/Bionic loader. */
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
extern void* reallocarray(void*, size_t, size_t);

int main(void) {
    /* An opaque call also checks allocator errno, without compiler assumptions
       about allocation-size attributes on a constant overflowing request. */
    void* (*volatile allocate)(void*, size_t, size_t) = reallocarray;
    unsigned char* memory = allocate(NULL, 4, 8);
    if (!memory) return 1;
    memset(memory, 0x5a, 32);
    errno = 0;
    void* failed = allocate(memory, SIZE_MAX, 2);
    int failure_errno = errno;
    if (failed != NULL || failure_errno != ENOMEM) {
        fprintf(stderr, "Overflow result=%p errno=%d expected=%d\n",
                failed, failure_errno, ENOMEM);
        return 2;
    }
    for (size_t i = 0; i < 32; ++i) if (memory[i] != 0x5a) return 3;
    unsigned char* grown = allocate(memory, 8, 8);
    if (!grown) return 4;
    for (size_t i = 0; i < 32; ++i) if (grown[i] != 0x5a) return 5;
    free(grown);
    puts("CI reallocarray allocation, resize and overflow checks passed");
    return 0;
}
