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

/**
 * Components which wish to cache static final variables based on component settings
 * should place those settings inside another (presumably inner class) such that
 * they can easily check that the inner class is not referenced in the wrong order
 * <p/>
 * A sample usage is shown below
 * <pre>
 * public class Foo extends JPSXComponent {
 *    @Override void init() {
 *       MySettings.setComponent(this);
 *    }
 * <p/>
 *    private static class Settings extends FinalComponentSettings {
 *       public static final boolean someFlag = getComponent().getBooleanProperty("someFlag",true)
 *    }
 * }
 * </pre>
 */
public class FinalComponentSettings {
    private static JPSXComponent component;

    public static JPSXComponent getComponent() {
        if (component == null) {
            throw new InvalidConfigurationException("Attempted to access cached component settings before component initialization");
        }
        return component;
    }

    public static void setComponent(JPSXComponent component) {
        if (FinalComponentSettings.component != null)
            throw new InvalidConfigurationException("Attempted to set component more than once");
        FinalComponentSettings.component = component;
    }
}
