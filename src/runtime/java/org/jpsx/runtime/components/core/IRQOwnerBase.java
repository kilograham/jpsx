/*
Copyright (C) 2007 graham sanderson

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation; either version 2
of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
*/
package org.jpsx.runtime.components.core;

import org.apache.log4j.Logger;
import org.jpsx.api.components.core.irq.IRQController;
import org.jpsx.api.components.core.irq.IRQOwner;

public abstract class IRQOwnerBase implements IRQOwner {
    public static final Logger log = Logger.getLogger(IRQControllerImpl.CATEGORY);
    protected int irq;
    protected String name;
    private IRQController controller;

    public IRQOwnerBase(int irq, String name) {
        this.irq = irq;
        this.name = name;
    }

    public void register(IRQController controller) {
        this.controller = controller;
        log.info("registering IRQ " + getName());
        controller.registerIRQOwner(this);
    }

    public final int getIRQ() {
        return irq;
    }

    public final String getName() {
        return name;
    }

    public void raiseIRQ() {
        controller.raiseIRQ(getIRQ());
    }

    // in case the device needs to know
    public void irqSet() {
    }

    // in case the device needs to know
    public void irqCleared() {
    }
}
