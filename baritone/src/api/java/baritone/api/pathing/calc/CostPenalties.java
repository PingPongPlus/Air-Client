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

package baritone.api.pathing.calc;

/**
 * Air Client addition: extra cost multipliers for single blocks. The client
 * mod puts blocks where the bot got stuck before in here; every new path
 * calculation multiplies the cost of moving into such a block, so the next
 * path goes around it. Kept in its own class so setting it never initialises
 * BaritoneAPI early.
 */
public final class CostPenalties {

    @FunctionalInterface
    public interface Sink {
        void put(int x, int y, int z, double multiplier);
    }

    @FunctionalInterface
    public interface Provider {
        /** Called once per path calculation, off the client thread: must be fast and thread safe. */
        void forEach(Sink sink);
    }

    private static volatile Provider provider;

    private CostPenalties() {
    }

    public static void set(Provider newProvider) {
        provider = newProvider;
    }

    public static Provider get() {
        return provider;
    }
}
