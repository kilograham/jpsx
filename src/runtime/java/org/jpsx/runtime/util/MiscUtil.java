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
package org.jpsx.runtime.util;

public class MiscUtil {
    public static String toHex(int val, int digits) {
        String rc = Integer.toHexString(val);
        if (rc.length() != digits) {
            rc = "0000000000000000" + rc;
            rc = rc.substring(rc.length() - digits, rc.length());
        }
        return rc;
    }

    protected static String ALPHA = "0123456789abcdef";

    public static int parseHex(String hex) {
        int rc = 0;
        hex = hex.trim().toLowerCase();
        for (int i = 0; i < hex.length(); i++) {
            int val = ALPHA.indexOf(hex.charAt(i));
            if (val == -1) {
                break;
            }
            rc = (rc << 4) + val;
        }
        return rc;
    }

}
