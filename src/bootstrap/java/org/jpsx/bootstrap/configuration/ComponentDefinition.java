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
package org.jpsx.bootstrap.configuration;

import org.jpsx.bootstrap.util.CollectionsFactory;

import java.util.Map;

public class ComponentDefinition {
    private String className;
    private Map<String, String> properties = CollectionsFactory.newHashMap();

    public ComponentDefinition(String className) {
        this.className = className;
    }

    public void setProperty(String property, String value) {
        properties.put(property, value);
    }

    public String getClassName() {
        return className;
    }

    public Map<String, String> getProperties() {
        return properties;
    }
}
