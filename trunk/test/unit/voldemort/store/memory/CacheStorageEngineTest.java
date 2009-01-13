/*
 * Copyright 2008-2009 LinkedIn, Inc
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package voldemort.store.memory;

import java.util.List;

import voldemort.TestUtils;
import voldemort.store.StorageEngine;
import voldemort.versioning.Versioned;

/**
 * Does all the normal tests but also uses a high memory pressure test to make
 * sure that values are collected.
 * 
 * @author jkreps
 * 
 */
public class CacheStorageEngineTest extends InMemoryStorageEngineTest {

    private static final int NUM_OBJECTS = 1000;

    public void setUp() throws Exception {
        super.setUp();
        System.gc();
    }

    public StorageEngine<byte[], byte[]> getStorageEngine() {
        return new CacheStorageConfiguration().getStore("test");
    }

    public void testNoPressureBehavior() {
        StorageEngine<byte[], byte[]> engine = getStorageEngine();
        byte[] bytes = "abc".getBytes();
        engine.put(bytes, new Versioned<byte[]>(bytes));
        List<Versioned<byte[]>> found = engine.get(bytes);
        assertEquals(1, found.size());
    }

    public void testHighMemoryCollection() {
        long maxMemory = Runtime.getRuntime().maxMemory();
        int objectSize = Math.max((int) maxMemory / NUM_OBJECTS, 1);
        StorageEngine<byte[], byte[]> engine = getStorageEngine();
        for(int i = 0; i < NUM_OBJECTS; i++)
            engine.put(Integer.toString(i).getBytes(),
                       new Versioned<byte[]>(TestUtils.randomBytes(objectSize)));
    }

}
