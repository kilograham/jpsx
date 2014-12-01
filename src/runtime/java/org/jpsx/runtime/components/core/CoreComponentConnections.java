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

import org.jpsx.api.CPUListener;
import org.jpsx.api.components.core.addressspace.AddressSpace;
import org.jpsx.api.components.core.addressspace.AddressSpaceListener;
import org.jpsx.api.components.core.addressspace.MemoryMapped;
import org.jpsx.api.components.core.cpu.*;
import org.jpsx.api.components.core.dma.DMAChannelOwner;
import org.jpsx.api.components.core.dma.DMAController;
import org.jpsx.api.components.core.irq.IRQController;
import org.jpsx.api.components.core.irq.IRQOwner;
import org.jpsx.api.components.core.scheduler.Quartz;
import org.jpsx.api.components.core.scheduler.Scheduler;
import org.jpsx.bootstrap.connection.MultipleConnection;
import org.jpsx.bootstrap.connection.SimpleConnection;

public class CoreComponentConnections {
    public static final SimpleConnection<R3000> R3000 = SimpleConnection.create("R3000", R3000.class);
    public static final SimpleConnection<SCP> SCP = SimpleConnection.create("SCP", SCP.class);
    public static final SimpleConnection<Quartz> QUARTZ = SimpleConnection.create("QUARTZ", Quartz.class);
    public static final SimpleConnection<Scheduler> SCHEDULER = SimpleConnection.create("Scheduler", Scheduler.class);
    public static final SimpleConnection<AddressSpace> ADDRESS_SPACE = SimpleConnection.create("Address Space", AddressSpace.class);
    public static final SimpleConnection<IRQController> IRQ_CONTROLLER = SimpleConnection.create("IRQ Controller", IRQController.class);
    public static final SimpleConnection<DMAController> DMA_CONTROLLER = SimpleConnection.create("DMA Controller", DMAController.class);
    public static final SimpleConnection<NativeCompiler> NATIVE_COMPILER = SimpleConnection.create("Native Compiler", NativeCompiler.class);

    public static final MultipleConnection<CPUListener> CPU_LISTENERS = MultipleConnection.create("CPU Listeners", CPUListener.class);
    public static final MultipleConnection<InstructionProvider> INSTRUCTION_PROVIDERS = MultipleConnection.create("Instruction Providers", InstructionProvider.class);
    public static final MultipleConnection<AddressSpaceListener> ADDRESS_SPACE_LISTENERS = MultipleConnection.create("Address Space Listeners", AddressSpaceListener.class);
    public static final MultipleConnection<IRQOwner> IRQ_OWNERS = MultipleConnection.create("IRQ Owners", IRQOwner.class);
    public static final MultipleConnection<DMAChannelOwner> DMA_CHANNEL_OWNERS = MultipleConnection.create("DMA Channel Owners", DMAChannelOwner.class);
    public static final MultipleConnection<MemoryMapped> ALL_MEMORY_MAPPED = MultipleConnection.create("Memory Mapped", MemoryMapped.class);
    public static final MultipleConnection<Runnable> ALL_POPULATORS = MultipleConnection.create("Memory Populators", Runnable.class);
    public static final MultipleConnection<PollBlockListener> POLL_BLOCK_LISTENERS = MultipleConnection.create("Poll Block Listeners", PollBlockListener.class);
}
