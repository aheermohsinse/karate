/*
 * The MIT License
 *
 * Copyright 2017 Intuit Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.intuit.karate;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import org.slf4j.Logger;

/**
 * this class exists as a performance optimization - we init Nashorn only once
 * and set up the Bindings to Karate variables only once per scenario
 * 
 * we also avoid re-creating hash-maps as far as possible
 * 
 * @author pthomas3
 */
public class ScriptBindings implements Bindings {
    
    // all threads will share this ! only for pure JS eval with ZERO bindings
    private static final ScriptEngine NASHORN = getNashorn(null);

    private final Logger logger;
    private final ScriptContext context;
    private final ScriptValueMap vars;
    private final ScriptBridge bridge;
    private final ScriptEngine nashorn;
    private final Map<String, Object> adds;

    public ScriptBindings(ScriptContext context) {
        this.context = context;
        this.logger = context.env.logger;
        this.vars = context.vars;
        this.adds = new HashMap(6); // read, karate, self, root, parent, nashorn.global
        this.bridge = new ScriptBridge(context);
        this.nashorn = getNashorn(this);
    }

    private static ScriptEngine getNashorn(Bindings bindings) {
        ScriptEngine nashorn = new ScriptEngineManager(null).getEngineByName("nashorn");
        if (bindings != null) {
            nashorn.setBindings(bindings, javax.script.ScriptContext.ENGINE_SCOPE);
        }
        return nashorn;
    }

    private ScriptEngine getNashorn(ScriptValue selfValue, Object root, Object parent) {
        adds.clear();
        adds.put(ScriptContext.KARATE_NAME, bridge);
        if (context.readFunction != null) {
            adds.put(ScriptContext.VAR_READ, context.readFunction.getValue());
        }
        if (selfValue != null) {
            adds.put(Script.VAR_SELF, selfValue.getAfterConvertingFromJsonOrXmlIfNeeded());
        }
        if (root != null) {
            adds.put(Script.VAR_ROOT, new ScriptValue(root).getAfterConvertingFromJsonOrXmlIfNeeded());
        }
        if (parent != null) {
            adds.put(Script.VAR_PARENT, new ScriptValue(parent).getAfterConvertingFromJsonOrXmlIfNeeded());
        }
        return nashorn;
    }

    public static ScriptValue evalInNashorn(String exp, ScriptContext context, ScriptValue selfValue, Object root, Object parent) {
        ScriptEngine nashorn = context == null ? NASHORN : context.bindings.getNashorn(selfValue, root, parent);
        try {
            Object o = nashorn.eval(exp);
            return new ScriptValue(o);
        } catch (Exception e) {
            throw new RuntimeException("javascript evaluation failed: " + exp, e);
        }
    }

    @Override
    public Object get(Object key) {
        ScriptValue sv = vars.get(key);
        if (sv == null) {
            return adds.get(key);
        }
        return sv.getAfterConvertingFromJsonOrXmlIfNeeded();
    }

    @Override
    public Object put(String name, Object value) {
        return adds.put(name, value);
    }

    @Override
    public void putAll(Map<? extends String, ? extends Object> toMerge) {
        adds.putAll(toMerge);
    }

    @Override
    public boolean containsKey(Object key) {
        // this has to be implemented correctly ! else nashorn won't return 'undefined'
        return vars.containsKey(key) || adds.containsKey(key);
    }    
    
    @Override
    public int size() {
        return vars.size() + adds.size();
    }    
    
    // these are never called by nashorn =======================================    

    @Override
    public boolean isEmpty() {
        return false;
    }

    @Override
    public Set<String> keySet() {
        Set<String> keys = new HashSet(vars.keySet());
        keys.addAll(adds.keySet());
        return keys;
    }

    @Override
    public Object remove(Object key) {
        return adds.remove(key);
    }

    @Override
    public boolean containsValue(Object value) {
        return adds.containsValue(value);
    }

    @Override
    public void clear() {
        adds.clear();
    }

    @Override
    public Collection<Object> values() {
        return adds.values();
    }

    @Override
    public Set<Entry<String, Object>> entrySet() {
        return adds.entrySet();
    }

}
