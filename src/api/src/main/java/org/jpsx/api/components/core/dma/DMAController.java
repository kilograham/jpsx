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
package org.jpsx.api.components.core.dma;

public interface DMAController {
    public static final int DMA_MDEC_IN = 0;
    public static final int DMA_MDEC_OUT = 1;
    public static final int DMA_GPU = 2;
    public static final int DMA_CD = 3;
    public static final int DMA_SPU = 4;
    public static final int DMA_GPU_OTC = 6;

    void registerDMAChannel(DMAChannelOwner owner);

    void dmaChannelTransferComplete(DMAChannelOwner owner);

    void dmaChannelTransferComplete(DMAChannelOwner owner, boolean interrupt);
}
