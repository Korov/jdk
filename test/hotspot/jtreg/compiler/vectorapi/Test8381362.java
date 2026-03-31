/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package compiler.vectorapi;

import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorShuffle;

import java.lang.invoke.VarHandle;

/*
 * @test
 * @bug 8381362
 * @summary C2 crashes with deferred Vector API late inlines when incremental
 *          MH and virtual inlining are disabled
 * @requires vm.debug == true & vm.compiler2.enabled
 * @modules jdk.incubator.vector
 * @run main/othervm/timeout=300
 *                   -Xcomp
 *                   -XX:-IncrementalInlineVirtual
 *                   -XX:-IncrementalInlineMH
 *                   -XX:-UseInlineCaches
 *                   compiler.vectorapi.Test8381362
 */
public class Test8381362 {
    private static final int SIZE = 60_000;

    public static void main(String[] args) {
        char[] a = new char[SIZE];
        char[] b = new char[SIZE];
        for (int i = 0; i < SIZE; i++) {
            a[i] = b[i] = (char) i;
        }
        arrayAbs(a);
        System.out.println("PASS");
    }

    private static void arrayAbs(char[] arr) {
        FloatVector first = null;
        FloatVector second = null;
        float[] out = new float[100];
        float[] src1 = new float[100];
        float[] src2 = new float[100];
        Object lock = new Object();

        for (int i = 0; i < src1.length; i++) {
            src1[i] = ((float) i) * 1.5F + 7.89F;
            src2[i] = ((float) i) * 1.5F + 7.89F;
            out[i] = ((float) i) * 1.5F + 7.89F;
        }

        for (int i = 0; i < 50; i++) {
            VarHandle.fullFence();
            synchronized (lock) {
                try {
                    lock.wait(1);
                } catch (InterruptedException ignored) {
                }
            }
            VarHandle.fullFence();

            if (((int) out[0]) % 3 < 1) {
                first = (FloatVector) VectorShuffle.iota(FloatVector.SPECIES_PREFERRED, 0, 14, true).toVector();
            } else {
                first = FloatVector.fromArray(FloatVector.SPECIES_PREFERRED, src1, 14);
            }

            first = FloatVector.fromArray(FloatVector.SPECIES_PREFERRED, src2, 14);
            second = first.add(first);
            second.intoArray(out, 14);

            synchronized (lock) {
                try {
                    lock.wait(1);
                } catch (InterruptedException ignored) {
                }
            }
        }
    }
}
