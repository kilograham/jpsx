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
package org.jpsx.runtime.components.hardware.bios;

import org.jpsx.api.InvalidConfigurationException;
import org.jpsx.api.components.core.addressspace.AddressSpace;
import org.jpsx.runtime.JPSXComponent;
import org.jpsx.runtime.components.core.CoreComponentConnections;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;

/**
 * ImageBIOS is a simple component which maps
 * a file base image of an R3000 BIOS into
 * the PSX address base. This file can be
 * a real PSX BIOS image, though it need not be
 */
public class ImageBIOS extends JPSXComponent {
    private static final int ADDRESS = 0xbfc00000;
    // todo get size from file!
    private static final int SIZE = 0x80000;

    public ImageBIOS() {
        super("JPSX BIOS using ROM image");
    }

    public void init() {
        super.init();
        CoreComponentConnections.ALL_POPULATORS.add(new Runnable() {
            public void run() {
                populateMemory();
            }
        });
    }

    private void populateMemory() {
        String filename = "bios.bin";
        //MiscUtil.consoleOut( "Loading bios " + filename + "..." );
        try {
            RandomAccessFile in = new RandomAccessFile(filename, "r");
            AddressSpace.ResolveResult rr = new AddressSpace.ResolveResult();
            AddressSpace addressSpace = CoreComponentConnections.ADDRESS_SPACE.resolve();
            addressSpace.resolve(ADDRESS, SIZE, true, rr);

            FileChannel channel = in.getChannel();
            ByteBuffer bytebuf = ByteBuffer.allocateDirect(SIZE);
            bytebuf.order(ByteOrder.LITTLE_ENDIAN);
            IntBuffer intbuf = bytebuf.asIntBuffer();
            bytebuf.clear();

            if (SIZE != channel.read(bytebuf, 0)) {
                throw new InvalidConfigurationException("BIOS image " + filename + " is not the correct size");
            }
            intbuf.rewind();
            intbuf.get(rr.mem);
            channel.close();
        } catch (IOException e) {
            throw new InvalidConfigurationException("Can't load BIOS image " + filename, e);
        }
    }
}
