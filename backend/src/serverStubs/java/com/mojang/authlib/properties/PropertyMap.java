package com.mojang.authlib.properties;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stub of authlib 1.5.16 PropertyMap. The real class extends Guava's ForwardingMultimap, which declares these two
 * methods; calls compiled against this stub resolve to them at run time without naming any Guava type.
 */
public class PropertyMap {
    private final Map<Object, List<Property>> entries = new HashMap<Object, List<Property>>();

    public Collection<Property> get(Object key) {
        List<Property> values = entries.get(key);
        return values == null ? new ArrayList<Property>() : new ArrayList<Property>(values);
    }

    public boolean remove(Object key, Object value) {
        List<Property> values = entries.get(key);
        return values != null && values.remove(value);
    }

    /** Test helper only; the backend never calls it. */
    public boolean put(String key, Property value) {
        List<Property> values = entries.get(key);
        if (values == null) entries.put(key, values = new ArrayList<Property>());
        return values.add(value);
    }
}
