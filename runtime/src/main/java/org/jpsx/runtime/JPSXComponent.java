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

import java.util.Properties;

public abstract class JPSXComponent {
    private Properties properties = new Properties();
    private String description;

    protected JPSXComponent(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public void init() {
    }

    /**
     * Called during machine initialization to connect the components together
     * <p/>
     * Subclasses should override this to
     */
    public void resolveConnections() {
    }

    public void begin() {
    }

    public void setProperty(String name, String value) {
        properties.put(name, value);
    }

    public String getProperty(String name, String defaultValue) {
        return properties.getProperty(name, defaultValue);
    }

    public boolean getBooleanProperty(String name, boolean defaultValue) {
        String val = properties.getProperty(name, String.valueOf(defaultValue));
        return Boolean.valueOf(val);
    }

    public int getIntProperty(String name, int defaultValue) {
        int rc = defaultValue;
        String val = properties.getProperty(name);
        if (val != null) {
            try {
                rc = Integer.parseInt(val);
            } catch (NumberFormatException e) {

            }
        }
        return rc;
    }

    protected Properties getProperties() {
        return properties;
    }
}

