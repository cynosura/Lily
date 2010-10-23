/*
 * Copyright 2010 Outerthought bvba
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lilyproject.repository.api;

import java.util.Arrays;

/**
 * A hierarchical path value. This kind of value specifies a hierarchical path consisting of path segments.
 *
 * <p>See {@link ValueType}.
 *
 * <p>A HierarchyPath is mutable: the elements array is not cloned internally.
 */
public class HierarchyPath {

    private final Object[] elements;

    public HierarchyPath(Object... elements) {
        this.elements = elements;
    }
    
    public Object[] getElements() {
        return elements;
    }
    
    public int length() {
        return elements.length;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + Arrays.hashCode(elements);
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        HierarchyPath other = (HierarchyPath) obj;
        if (!Arrays.equals(elements, other.elements))
            return false;
        return true;
    }

    @Override
    public String toString() {
        return Arrays.toString(elements);
    }
}