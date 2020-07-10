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
import org.jpsx.api.components.core.addressspace.AddressSpaceRegistrar;
import org.jpsx.api.components.core.addressspace.MemoryMapped;
import org.jpsx.api.components.core.cpu.SCP;
import org.jpsx.api.components.core.irq.IRQController;
import org.jpsx.api.components.core.irq.IRQOwner;
import org.jpsx.runtime.JPSXMachine;
import org.jpsx.runtime.RuntimeConnections;
import org.jpsx.runtime.SingletonJPSXComponent;

// todo threading
public class IRQControllerImpl extends SingletonJPSXComponent implements MemoryMapped, IRQController {
    static final String CATEGORY = "IRQ";
    private static final Logger log = Logger.getLogger(CATEGORY);

    private static int IRQ_COUNT = 8;
    private static IRQOwner[] owners = new IRQOwner[IRQ_COUNT];

    private static final int ALL_INTERRUPTS = 0xff;

    private static final int ADDR_IRQ_REQUEST = 0x1f801070;
    private static final int ADDR_IRQ_MASK = 0x1f801074;

    private static int irqRequest;
    private static int irqMask;
    private static SCP scp;

    public IRQControllerImpl() {
        super("JPSX IRQ Controller");
    }

    @Override
    public void init() {
        super.init();
        CoreComponentConnections.IRQ_CONTROLLER.set(this);
        JPSXMachine machine = RuntimeConnections.MACHINE.resolve();
        machine.addInitializer(JPSXMachine.PRIORITY_IRQ_CONTROLLER, new Runnable() {
            public void run() {
                log.info("Initializing IRQ owners...");
                // get all IRQOwners to register
                CoreComponentConnections.IRQ_OWNERS.resolve().register(IRQControllerImpl.this);
            }
        });
        CoreComponentConnections.ALL_MEMORY_MAPPED.add(this);
    }

    @Override
    public void resolveConnections() {
        super.resolveConnections();
        scp = CoreComponentConnections.SCP.resolve();
    }

    public void registerAddresses(AddressSpaceRegistrar registrar) {
        registrar.registerWrite32Callback(ADDR_IRQ_REQUEST, IRQControllerImpl.class, "irqRequestWrite32", true);
        registrar.registerRead32Callback(ADDR_IRQ_REQUEST, IRQControllerImpl.class, "irqRequestRead32", true);

        registrar.registerWrite32Callback(ADDR_IRQ_MASK, IRQControllerImpl.class, "irqMaskWrite32", true);
        registrar.registerRead32Callback(ADDR_IRQ_MASK, IRQControllerImpl.class, "irqMaskRead32", true);
    }

    public void registerIRQOwner(IRQOwner owner) {
        int irq = owner.getIRQ();
        owners[irq] = owner;
    }

    public static synchronized void irqRequestWrite32(int addr, int value, int mask) {
        irqRequest &= (~mask) | (value & mask);
        updateInterruptLine();
    }

    public static int irqRequestRead32(int addr) {
        return irqRequest;
    }

    public static void irqMaskWrite32(int addr, int value, int mask) {
        irqMask = (irqMask & ~mask) | (value & mask);
    }

    public static int irqMaskRead32(int addr) {
        return irqMask;
    }

    @Override
    public void raiseIRQ(int irq) {
        _raiseIRQ(irq);
    }

    public static synchronized void _raiseIRQ(int irq) {
        irqRequest |= (1 << irq);
        updateInterruptLine();
    }

    private static int oldRequest;

    private static void updateInterruptLine() {
        assert Thread.holdsLock(IRQControllerImpl.class);
        int changes = oldRequest ^ irqRequest;
        oldRequest = irqRequest;
        int irq = 0;
        while (irq < IRQ_COUNT && changes != 0) {
            if (0 != (changes & 1)) {
                IRQOwner owner = owners[irq];
                if (owner != null) {
                    if (0 != (irqRequest & (1 << irq))) {
                        owner.irqSet();
                    } else {
                        owner.irqCleared();
                    }
                }
            }
            changes >>= 1;
            irq++;
        }
        scp.setInterruptLine(2, 0 != ((irqMask & irqRequest) & ALL_INTERRUPTS));
    }

    public int getIRQRequest() {
        return irqRequest;
    }

    public int getIRQMask() {
        return irqMask;
    }
}
