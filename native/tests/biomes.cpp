#include <cstdio>
#include "postprocess.h"

int main() {
    const fd::BiomeInputs meadow{1200, 1200, 0.12f, 12, 500, 450, 20, 0};
    int failures = 0;
    const auto check = [&](const char* name, const fd::BiomeInputs& in, bool expected) {
        const bool found = fd::classify_biome(in) == fd::MEADOW;
        if (found != expected) { std::fprintf(stderr, "%s: unexpected biome %d\n", name, fd::classify_biome(in)); failures++; }
    };
    check("cool upland grassland", meadow, true);
    auto in = meadow; in.elev = in.band_elev = 100; check("lowland", in, false);
    in = meadow; in.elev = in.band_elev = 2700; check("alpine", in, false);
    in = meadow; in.slope = 0.8f; check("steep exposed ground", in, false);
    in = meadow; in.temp = -2; check("snow", in, false);
    in = meadow; in.temp = 28; check("hot ground", in, false);
    in = meadow; in.precip = 50; check("arid ground", in, false);
    in = meadow; in.precip = 2000; check("dense forest", in, false);
    in = meadow; in.elev = -20; check("ocean", in, false);
    return failures ? 1 : 0;
}
