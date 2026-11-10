/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.pathing.calc;

import baritone.pathing.movement.CalculationContext;
import baritone.pathing.movement.MovementHelper;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;

/**
 * Air Client: rates standing positions by how safe they are to walk over, not
 * only by how fast. A position next to a drop or squeezed against a wall gets
 * a small extra cost per such side, so A* keeps a gap to edges and corners
 * when there is room, and still uses a narrow bridge when there is no other
 * way (it is a surcharge, never a ban). Each position is rated once per search.
 */
final class EdgeSafety {
    private static final int[] DX = {1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 1, -1};

    private final CalculationContext context;
    private final double edgeCost;
    private final double wallCost;
    private final Long2DoubleOpenHashMap cache = new Long2DoubleOpenHashMap();

    EdgeSafety(CalculationContext context) {
        this.context = context;
        this.edgeCost = context.edgeSafetyCost;
        this.wallCost = context.wallSafetyCost;
        cache.defaultReturnValue(-1);
    }

    boolean enabled() {
        return edgeCost > 0 || wallCost > 0;
    }

    /** Extra ticks for standing at x, y, z (y = feet). */
    double extraCost(int x, int y, int z, long hash) {
        double cached = cache.get(hash);
        if (cached >= 0) {
            return cached;
        }
        double extra = compute(x, y, z);
        cache.put(hash, extra);
        return extra;
    }

    private double compute(int x, int y, int z) {
        // Swimming, climbing or falling positions: edges do not matter there.
        if (!MovementHelper.canWalkOn(context, x, y - 1, z)) {
            return 0;
        }
        double extra = 0;
        for (int i = 0; i < 4; i++) {
            int nx = x + DX[i];
            int nz = z + DZ[i];
            if (!context.isLoaded(nx, nz)) {
                continue; // unknown, do not guess
            }
            boolean feetFree = MovementHelper.canWalkThrough(context, nx, y, nz);
            boolean headFree = MovementHelper.canWalkThrough(context, nx, y + 1, nz);
            if (!feetFree || !headFree) {
                extra += wallCost;
                continue;
            }
            if (edgeCost > 0
                    && !MovementHelper.canWalkOn(context, nx, y - 1, nz)
                    && !MovementHelper.canWalkOn(context, nx, y - 2, nz)) {
                extra += edgeCost;
            }
        }
        return extra;
    }
}
