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
package org.jpsx.runtime.components.hardware.sio;

import org.jpsx.bootstrap.util.CollectionsFactory;
import org.jpsx.runtime.RuntimeConnections;
import org.jpsx.runtime.components.hardware.HardwareComponentConnections;

import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.util.Map;

// todo allow different mappings
public class AWTKeyboardController extends StandardController implements KeyListener {
    protected KeyMapping mapping;

    protected static final KeyMapping DEF_CONTROLLER_0_MAPPING;

    static {
        DEF_CONTROLLER_0_MAPPING = new KeyMapping();

        DEF_CONTROLLER_0_MAPPING.put(PADstart, KeyEvent.VK_SPACE);
        DEF_CONTROLLER_0_MAPPING.put(PADselect, KeyEvent.VK_S);
        DEF_CONTROLLER_0_MAPPING.put(PADLup, KeyEvent.VK_UP);
        DEF_CONTROLLER_0_MAPPING.put(PADLleft, KeyEvent.VK_LEFT);
        DEF_CONTROLLER_0_MAPPING.put(PADLright, KeyEvent.VK_RIGHT);
        DEF_CONTROLLER_0_MAPPING.put(PADLdown, KeyEvent.VK_DOWN);
        DEF_CONTROLLER_0_MAPPING.put(PADRup, KeyEvent.VK_8);
        DEF_CONTROLLER_0_MAPPING.put(PADRup, KeyEvent.VK_KP_UP);
        DEF_CONTROLLER_0_MAPPING.put(PADRdown, KeyEvent.VK_K);
        DEF_CONTROLLER_0_MAPPING.put(PADRleft, KeyEvent.VK_KP_LEFT);
        DEF_CONTROLLER_0_MAPPING.put(PADRright, KeyEvent.VK_I);
        DEF_CONTROLLER_0_MAPPING.put(PADRright, KeyEvent.VK_KP_RIGHT);
        DEF_CONTROLLER_0_MAPPING.put(PADRdown, KeyEvent.VK_KP_DOWN);
        DEF_CONTROLLER_0_MAPPING.put(PADRleft, KeyEvent.VK_U);
        DEF_CONTROLLER_0_MAPPING.put(PADL1, KeyEvent.VK_1);
        DEF_CONTROLLER_0_MAPPING.put(PADL2, KeyEvent.VK_Q);
        DEF_CONTROLLER_0_MAPPING.put(PADR1, KeyEvent.VK_2);
        DEF_CONTROLLER_0_MAPPING.put(PADR2, KeyEvent.VK_W);
    }

    public static class KeyMapping {
        private Map<Integer, Integer> map = CollectionsFactory.newHashMap();

        public int get(int vkey) {
            Integer mask = map.get(vkey);
            if (mask != null)
                return mask;
            return 0;
        }

        public void put(int mask, int vkey) {
            map.put(vkey, mask);
        }
    }

    public AWTKeyboardController() {
        this(DEF_CONTROLLER_0_MAPPING);
    }

    public AWTKeyboardController(KeyMapping mapping) {
        super("JPSX AWT Keyboard Controller");
        this.mapping = mapping;
    }

    @Override
    public void init() {
        super.init();
        RuntimeConnections.KEY_LISTENERS.add(this);
    }

    public void resolveConnections() {
        // for now just connect to the left serial port
        HardwareComponentConnections.LEFT_PORT_INSTANCE.resolve().connect(this);
    }

    public void keyTyped(KeyEvent e) {
    }

    public void keyPressed(KeyEvent e) {
        int vkey = e.getKeyCode();
        pressed(mapping.get(vkey));
    }

    public void keyReleased(KeyEvent e) {
        int vkey = e.getKeyCode();
        released(mapping.get(vkey));
    }
}
