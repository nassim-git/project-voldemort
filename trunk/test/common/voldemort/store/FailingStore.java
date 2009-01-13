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

package voldemort.store;

import java.util.List;

import voldemort.VoldemortException;
import voldemort.versioning.Version;
import voldemort.versioning.Versioned;

import com.google.common.base.Objects;

/**
 * A store that always throws an exception for every operation
 * 
 * @author jay
 * 
 */
public class FailingStore<K, V> implements Store<K, V> {

    private final String name;
    private final VoldemortException exception;

    public FailingStore(String name) {
        this(name, new VoldemortException("Operation failed!"));
    }

    public FailingStore(String name, VoldemortException e) {
        this.name = Objects.nonNull(name);
        this.exception = e;
    }

    public void close() throws VoldemortException {
        throw exception;
    }

    public List<Versioned<V>> get(K key) throws VoldemortException {
        throw exception;
    }

    public String getName() {
        return name;
    }

    public boolean delete(K key, Version value) throws VoldemortException {
        throw exception;
    }

    public void put(K key, Versioned<V> value) throws VoldemortException {
        throw exception;
    }

}
