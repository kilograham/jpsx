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
package org.jpsx.bootstrap.util;

import java.util.*;

public class CollectionsFactory {
    public static <V> Set<V> newHashSet() {
        return new HashSet<V>();
    }

    public static <K, V> Map<K, V> newHashMap() {
        return new HashMap<K, V>();
    }

    public static <K, V> Map<K, V> newTreeMap() {
        return new TreeMap<K, V>();
    }

    public static <K, V> Map<K, V> newTreeMap(Comparator<? super K> c) {
        return new TreeMap<K, V>(c);
    }

    public static <V> List<V> newArrayList() {
        return new ArrayList<V>();
    }
}
