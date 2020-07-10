/*
 * Copyright (C) 2003, 2014 Graham Sanderson
 *
 * This file is part of JPSX.
 * 
 * JPSX is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JPSX is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with JPSX.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.jpsx.api.components.core.irq;

public interface IRQController {
    public static final int IRQ_VSYNC = 0;
    public static final int IRQ_GPU = 1;
    public static final int IRQ_CD = 2;
    public static final int IRQ_DMA = 3;
    public static final int IRQ_COUNTER0 = 4;
    public static final int IRQ_COUNTER1 = 5;
    public static final int IRQ_COUNTER2 = 6;
    public static final int IRQ_SIO0 = 7;

    void registerIRQOwner(IRQOwner owner);

    // todo allow queue (some sort of delivery guarantee)
    /**
     * Raise the given IRQ; this method may be called from any thread
     *
     * @param irq
     */
    void raiseIRQ(int irq);

    int getIRQRequest();

    int getIRQMask();
}
