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
package org.jpsx.api.components.core.addressspace;

public interface AddressSpaceRegistrar {
    void registerRead8Callback(int address, Class clazz, String methodName);

    void registerRead16Callback(int address, Class clazz, String methodName);

    void registerRead16Callback(int address, Class clazz, String methodName, boolean allowSubRead);

    void registerRead32Callback(int address, Class clazz, String methodName, boolean allowSubRead);

    void registerRead32Callback(int address, Class clazz, String methodName);

    void registerWrite8Callback(int address, Class clazz, String methodName);

    void registerWrite16Callback(int address, Class clazz, String methodName);

    void registerWrite16Callback(int address, Class clazz, String methodName, boolean allowSubWrite);

    void registerWrite32Callback(int address, Class clazz, String methodName);

    void registerWrite32Callback(int address, Class clazz, String methodName, boolean allowSubWrite);

    void registerPoll32Callback(int address, Pollable pollable);
}
