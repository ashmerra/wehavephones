#include <assert.h>
#include <stdio.h>
#include "../../app/src/main/cpp/wehavephones/drawable_copy.h"

int main(void) {
    uint32_t source[16], dest[16];
    for (int i = 0; i < 16; i++) { source[i] = i + 1; dest[i] = 0; }
    drawable_copy_area((uint8_t *)source, (uint8_t *)dest, 0, 0, 0, 0, 2, 2, 4, 4);
    for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++)
        assert(dest[y * 4 + x] == (x < 2 && y < 2 ? source[y * 4 + x] : 0));
    // Copying a narrow surface must preserve padding in each destination row.
    memset(dest, 0, sizeof(dest));
    drawable_copy_area((uint8_t *)source, (uint8_t *)dest, 1, 0, 0, 1, 3, 2, 4, 4);
    assert(dest[4] == 2 && dest[6] == 4 && dest[7] == 0);
    assert(dest[8] == 6 && dest[10] == 8 && dest[11] == 0);
    // Downward and horizontal overlap read original pixels.
    memcpy(dest, source, sizeof(dest));
    drawable_copy_area((uint8_t *)dest, (uint8_t *)dest, 0, 0, 0, 1, 4, 3, 4, 4);
    for (int i = 4; i < 16; i++) assert(dest[i] == source[i - 4]);
    memcpy(dest, source, sizeof(dest));
    drawable_copy_area((uint8_t *)dest, (uint8_t *)dest, 0, 0, 1, 0, 3, 1, 4, 4);
    assert(dest[0] == 1 && dest[1] == 1 && dest[2] == 2 && dest[3] == 3);
    puts("DrawableCopySelfTest PASS");
}
