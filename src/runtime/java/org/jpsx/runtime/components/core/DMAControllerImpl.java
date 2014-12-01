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
import org.jpsx.api.components.core.addressspace.AddressSpace;
import org.jpsx.api.components.core.addressspace.AddressSpaceRegistrar;
import org.jpsx.api.components.core.addressspace.MemoryMapped;
import org.jpsx.api.components.core.dma.DMAChannelOwner;
import org.jpsx.api.components.core.dma.DMAController;
import org.jpsx.api.components.core.irq.IRQController;
import org.jpsx.runtime.JPSXMachine;
import org.jpsx.runtime.RuntimeConnections;
import org.jpsx.runtime.SingletonJPSXComponent;
import org.jpsx.runtime.util.MiscUtil;

// todo synch of DMA address control
//

// TODO check DMA_ICR - what is bit 23? - what are low bits?

public class DMAControllerImpl extends SingletonJPSXComponent implements DMAController, MemoryMapped {
    protected static final String CATEGORY = "DMA";
    private static final Logger log = Logger.getLogger(CATEGORY);
//$1f801080 DMA channel 0  MDECin
//$1f801090 DMA channel 1  MDECout
//$1f8010a0 DMA channel 2  GPU (lists + image data)
//$1f8010b0 DMA channel 3  CDrom
//$1f8010c0 DMA channel 4  SPU
//$1f8010d0 DMA channel 5  PIO
//$1f8010e0 DMA channel 6  OTC (reverse clear OT)

    protected static final String CLASS = DMAControllerImpl.class.getName();

    // todo settings
    public static final boolean debugDMA = false;
    private static AddressSpace addressSpace;

    private static final int ADDR_DMA_PCR = 0x1f8010f0;
    private static final int ADDR_DMA_ICR = 0x1f8010f4;
    private static final int CHANNEL_COUNT = 7;
    private static final int DMA_CTRL_TO_DEVICE = 0x00000001;
    private static final int DMA_CTRL_BUSY = 0x01000000;

    private static final int ADDR_DMA0_ADDR = 0x1f801080;
    private static final int ADDR_DMA0_SIZE = 0x1f801084;
    private static final int ADDR_DMA0_CTRL = 0x1f801088;

    private static final DMAChannelOwner[] dmaChannelOwners = new DMAChannelOwner[CHANNEL_COUNT];

    private static int pcr;
    private static int icr;

    private static IRQ irq;


    public DMAControllerImpl() {
        super("JPSX DMA Controller");
    }

    private static class IRQ extends IRQOwnerBase {
        public IRQ() {
            super(IRQController.IRQ_DMA, "DMA");
        }
    }

    @Override
    public void init() {
        super.init();
        CoreComponentConnections.DMA_CONTROLLER.set(this);
        CoreComponentConnections.ALL_MEMORY_MAPPED.add(this);
        JPSXMachine machine = RuntimeConnections.MACHINE.resolve();
        machine.addInitializer(JPSXMachine.PRIORITY_DMA_CONTROLLER, new Runnable() {
            public void run() {
                log.info("Initializing DMA channels...");
                // get all DMAChannels to register
                CoreComponentConnections.DMA_CHANNEL_OWNERS.resolve().register(DMAControllerImpl.this);
            }
        });
        irq = new IRQ();
        CoreComponentConnections.IRQ_OWNERS.add(irq);
    }

    public void registerAddresses(AddressSpaceRegistrar registrar) {
        registrar.registerWrite32Callback(ADDR_DMA_ICR, DMAControllerImpl.class, "dmaICRWrite32", true);
        registrar.registerWrite32Callback(ADDR_DMA_PCR, DMAControllerImpl.class, "dmaPCRWrite32");
        registrar.registerRead32Callback(ADDR_DMA_ICR, DMAControllerImpl.class, "dmaICRRead32", true);
        registrar.registerRead32Callback(ADDR_DMA_PCR, DMAControllerImpl.class, "dmaPCRRead32", true);
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            if (dmaChannelOwners[i] != null) {
                registrar.registerWrite32Callback(ADDR_DMA0_CTRL + i * 0x10, DMAControllerImpl.class, "dmaChannelCtrlWrite32");
            }
        }
    }

    @Override
    public void resolveConnections() {
        super.resolveConnections();
        addressSpace = CoreComponentConnections.ADDRESS_SPACE.resolve();
    }

    public void registerDMAChannel(DMAChannelOwner owner) {
        int channel = owner.getDMAChannel();
        dmaChannelOwners[channel] = owner;
    }

    public static void dmaICRWrite32(int address, int value, int mask) {
        assert address == ADDR_DMA_ICR;

        int oldICR = icr;
        // mask off dma channels' interrupt pending bits
        int newICR = ((icr & ~value) & 0xff000000) | (value & 0xffffff);
        // only affect the bits which were actually written by the cpu
        newICR = (oldICR & ~mask) | (newICR & mask);

        // todo what does bit 31 mean - i've seen *value = (*value & 0x00ffffff)|0x88000000;
        if (debugDMA) {
            if ((oldICR & 0xffff) != (newICR & 0xffff)) {
                log.warn("??? DMA ICR: low bits changed from " + MiscUtil.toHex(oldICR & 0xffff, 4) + " to " + MiscUtil.toHex(newICR & 0xffff, 4));
            }
            int changed = newICR ^ oldICR;
            for (int i = 0; i < 8; i++) {
                if (0 != (changed & (1 << (16 + i)))) {
                    if (0 != (newICR & (1 << (16 + i)))) {
                        System.out.println("DMA ICR: Enabling DMA IRQ for channel " + i);
                    } else {
                        System.out.println("DMA ICR: Disabling DMA IRQ for channel " + i);
                    }
                }
            }
        }
        icr = newICR;
    }

    public static int dmaICRRead32(int address) {
        return icr;
    }

    public static int dmaPCRRead32(int address) {
        return pcr;
    }

    public static void dmaPCRWrite32(int address, int value) {
        // todo is this true, perhaps bit3 per channel
        // is whether to have any effect on that channel
        if (debugDMA) {
            int changed = pcr ^ value;
            System.out.println("DMA PCR " + MiscUtil.toHex(value, 8));
            for (int i = 0; i < 8; i++) {
                if (0 != (changed & (1 << (3 + i * 4)))) {
                    if (0 != (value & (1 << (3 + i * 4)))) {
                        System.out.println("DMA PCR: Enabling DMA channel " + i);
                    } else {
                        System.out.println("DMA PCR: Disabling DMA channel " + i);
                    }
                }
            }
        }
        pcr = value;
    }

    public static void dmaChannelCtrlWrite32(int address, int ctrl) {
        int channel = (address - ADDR_DMA0_CTRL) / 0x10;
        //ASSERT( INVALID_PARAMETER, channel>=0, "");
        //ASSERT( INVALID_PARAMETER, channel<DMAChannel::COUNT, "");

        // for now write through as is
        addressSpace.internalWrite32(address, ctrl);
        if (dmaChannelOwners[channel] != null) {
            if ((ctrl & DMA_CTRL_BUSY) != 0) {
                int base = addressSpace.internalRead32(address + ADDR_DMA0_ADDR - ADDR_DMA0_CTRL);
                int size = addressSpace.internalRead32(address + ADDR_DMA0_SIZE - ADDR_DMA0_CTRL);
                int blockSize = size & 0xffff;
                int blocks = (size >> 16) & 0xffff;
                ;
                if ((ctrl & DMA_CTRL_TO_DEVICE) != 0) {
                    dmaChannelOwners[channel].beginDMATransferToDevice(base, blocks, blockSize, ctrl);
                } else {
                    dmaChannelOwners[channel].beginDMATransferFromDevice(base, blocks, blockSize, ctrl);
                }
            } else {
                dmaChannelOwners[channel].cancelDMATransfer(ctrl);
            }
        } else {
            if ((ctrl & DMA_CTRL_BUSY) != 0) {
                if ((ctrl & DMA_CTRL_TO_DEVICE) != 0) {
                    System.out.println("DMA to Unknown channel " + channel);
                } else {
                    System.out.println("DMA from Unknown channel " + channel);
                }
            } else {
                System.out.println("DMA cancel Unknown channel " + channel);
            }
        }
    }

    public void dmaChannelTransferComplete(DMAChannelOwner owner) {
        dmaChannelTransferComplete(owner, true);
    }

    public void dmaChannelTransferComplete(DMAChannelOwner owner, boolean interrupt) {
        int channel = owner.getDMAChannel();
        //ASSERT( INVALID_PARAMETER, channel>=0, "");
        //ASSERT( INVALID_PARAMETER, channel<DMAChannel::COUNT, "");
        //ASSERT1P( INVALID_STATE, m_dmaChannelOwners[channel]!=NULL, "DMA channel %d has no owner\n", channel);

        int address = ADDR_DMA0_CTRL + (channel * 0x10);

        // unmask busy bit
        addressSpace.internalWrite32(address, addressSpace.internalRead32(address) & ~DMA_CTRL_BUSY);

        if (interrupt) {
            if (0 != (icr & (1 << (channel + 16)))) {
                // todo, should this set bit 31?
                // set dma channel causing interrupt
                icr = icr | (1 << (channel + 24));
                // set DMA interrupt
                if (debugDMA) System.out.println("SIGNAL DMA INTERRUPT FOR " + owner.getName());
                irq.raiseIRQ();
            }
        }
    }
}

