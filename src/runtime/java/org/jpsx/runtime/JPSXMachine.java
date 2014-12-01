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
package org.jpsx.runtime;

public interface JPSXMachine {

    // initializer priorities; higher happens earliest

    int PRIORITY_RESOLVE_CONNECTIONS = 90000;
    int PRIORITY_ADD_INSTRUCTIONS = 50000;
    int PRIORITY_DMA_CONTROLLER = 40000;
    int PRIORITY_IRQ_CONTROLLER = 35000;
    int PRIORITY_REGISTER_ADDRESSES = 30000;
    int PRIORITY_POPULATE_MEMORY = 10000;
    int PRIORITY_FREEZE_SETTINGS = 5000;

    void addInitializer(int priority, Runnable initializer);

    void close();
}
