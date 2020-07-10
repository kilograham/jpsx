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
package org.jpsx.runtime.util;

import org.apache.bcel.generic.*;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;

// todo client VM only

public class ByteCodeUtil {
    public static void emitSwitch(ConstantPoolGen cp, InstructionList il, int localIndex, SortedMap<Integer, InstructionList> cases, int resolution, int lowBound, int highBound) {
        if (cases.size() == 0) return;
        if (true) {
            emitBinaryTree(cp, il, localIndex, cases, resolution, lowBound, highBound);
        }
    }

    public static void emitBinaryTree(ConstantPoolGen cp, InstructionList il, int localIndex, SortedMap<Integer, InstructionList> cases, int resolution, int lowBound, int highBound) {
        List<Integer> keys = new ArrayList<Integer>();
        keys.addAll(cases.keySet());
        emitBinaryTree(cp, il, localIndex, keys, cases, resolution, lowBound, highBound, 0, keys.size() - 1);
    }

    public static void emitBinaryTree(ConstantPoolGen cp, InstructionList il, int localIndex, List<Integer> keys, SortedMap<Integer, InstructionList> cases, int resolution, int lowBound, int highBound, int start, int end) {
        if (start == end) {
            IF_ICMPNE cmpne = null;
            Integer key = keys.get(start);
            boolean requireIf = key != lowBound || key != highBound;
            if (requireIf) {
                il.append(new ILOAD(localIndex));
                il.append(new PUSH(cp, key));
                cmpne = new IF_ICMPNE(null);
                il.append(cmpne);
            }
            il.append(cases.get(key));
            if (requireIf) {
                cmpne.setTarget(il.append(new NOP()));
            }
        } else {
            int middle = (start + end + 1) / 2;
            int middleValue = keys.get(middle);

            il.append(new ILOAD(localIndex));
            il.append(new PUSH(cp, middleValue));
            IF_ICMPGE cmpge = new IF_ICMPGE(null);
            il.append(cmpge);
            emitBinaryTree(cp, il, localIndex, keys, cases, resolution, lowBound, middleValue - resolution, start, middle - 1);
            GOTO gt = new GOTO(null);
            InstructionHandle gthandle = il.append(gt);
            emitBinaryTree(cp, il, localIndex, keys, cases, resolution, middleValue, highBound, middle, end);
            gt.setTarget(il.append(new NOP()));
            cmpge.setTarget(gthandle.getNext());
        }
    }
}
