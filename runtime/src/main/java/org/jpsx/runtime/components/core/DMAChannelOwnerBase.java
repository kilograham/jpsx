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
package org.jpsx.runtime.components.core;

import org.apache.log4j.Logger;
import org.jpsx.api.components.core.dma.DMAChannelOwner;
import org.jpsx.api.components.core.dma.DMAController;
import org.jpsx.runtime.util.MiscUtil;

public abstract class DMAChannelOwnerBase implements DMAChannelOwner {
    public static final Logger log = Logger.getLogger(DMAControllerImpl.CATEGORY);
    private DMAController dmaController;

    public abstract int getDMAChannel();


    public void register(DMAController controller) {
        log.info("Registering channel " + getName());
        dmaController = controller;
        controller.registerDMAChannel(this);
    }

    public String getName() {
        return "DMA device " + getDMAChannel();
    }

    public void beginDMATransferToDevice(int base, int blocks, int blockSize, int ctrl) {
        if (DMAControllerImpl.debugDMA)
            System.out.println("begin DMA transfer to " + getName() + " " + MiscUtil.toHex(base, 8) + " 0x" + Integer.toHexString(blocks) + "*0x" + Integer.toHexString(blockSize) + " ctrl " + MiscUtil.toHex(ctrl, 8));
        signalTransferComplete();
    }

    public void beginDMATransferFromDevice(int base, int blocks, int blockSize, int ctrl) {
        if (DMAControllerImpl.debugDMA)
            System.out.println("begin DMA transfer from " + getName() + " " + MiscUtil.toHex(base, 8) + " 0x" + Integer.toHexString(blocks) + "*0x" + Integer.toHexString(blockSize) + " ctrl " + MiscUtil.toHex(ctrl, 8));
        signalTransferComplete();
    }

    public void cancelDMATransfer(int ctrl) {
        if (DMAControllerImpl.debugDMA) System.out.println("cancel " + getName() + " DMA transfer");
    }

    public final void signalTransferComplete() {
        dmaController.dmaChannelTransferComplete(this);
    }

    public final void signalTransferComplete(boolean interrupt) {
        dmaController.dmaChannelTransferComplete(this, interrupt);
//        Console.println( getName()+" transfer complete");
    }
}
