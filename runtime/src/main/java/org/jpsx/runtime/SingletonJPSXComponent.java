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

import org.jpsx.api.InvalidConfigurationException;
import org.jpsx.bootstrap.util.CollectionsFactory;

import java.util.Set;

/**
 * Only one instance of a specific subclass may be created; this
 * does not stop you from creating two different classes which represent
 * the same piece of hardware
 */
public abstract class SingletonJPSXComponent extends JPSXComponent {
    private static final Set<String> usedClasses = CollectionsFactory.newHashSet();

    protected SingletonJPSXComponent(String description) {
        super(description);
    }

    @Override
    public void init() {
        super.init();
        if (!usedClasses.add(getClass().getName())) {
            throw new InvalidConfigurationException("Attempted to create multiple instances of " + getClass().getName());
        }
    }
}
