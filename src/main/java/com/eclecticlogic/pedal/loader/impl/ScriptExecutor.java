/**
 * Copyright (c) 2014 Eclectic Logic LLC
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */
package com.eclecticlogic.pedal.loader.impl;

import groovy.lang.Binding;
import groovy.lang.Closure;
import groovy.lang.GroovyShell;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

import org.apache.commons.io.IOUtils;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;

import com.eclecticlogic.pedal.Transaction;
import com.eclecticlogic.pedal.dm.DAORegistry;
import com.eclecticlogic.pedal.loader.LoaderExecutor;
import com.eclecticlogic.pedal.loader.Script;
import com.google.common.base.Strings;
import com.google.common.base.Throwables;

/**
 * @author kabram.
 */
public class ScriptExecutor implements LoaderExecutor {

    private String scriptDirectory;
    private Stack<ScriptContext> scriptContextStack = new Stack<>();

    private Map<String, Object> inputs = new HashMap<>();

    private DAORegistry daoRegistry;
    private Transaction transaction;


    public ScriptExecutor(DAORegistry daoRegistry, Transaction transaction) {
        this.daoRegistry = daoRegistry;
        this.transaction = transaction;
    }


    public void setScriptDirectory(String scriptDirectory) {
        this.scriptDirectory = scriptDirectory;
    }


    public void setInputs(Map<String, Object> inputs) {
        this.inputs = inputs;
    }


    @Override
    public Map<String, Object> load(String loadScript, String... additionalScripts) {
        List<String> scripts = new ArrayList<>();
        scripts.add(loadScript);
        if (additionalScripts != null) {
            for (int i = 0; i < additionalScripts.length; i++) {
                scripts.add(additionalScripts[i]);
            }
        }

        return load(scripts);
    }


    @Override
    public Map<String, Object> load(Script script, Script... additionalScripts) {
        List<Script> scripts = new ArrayList<>();
        scripts.add(script);
        if (additionalScripts != null) {
            for (int i = 0; i < additionalScripts.length; i++) {
                scripts.add(additionalScripts[i]);
            }
        }
        return loadNamespaced(scripts);
    }


    @Override
    public Map<String, Object> load(Collection<String> loadScripts) {
        List<Script> scripts = new ArrayList<>();
        for (String script : loadScripts) {
            scripts.add(Script.script(script));
        }
        return loadNamespaced(scripts);
    }


    @Override
    public Map<String, Object> loadNamespaced(Collection<Script> scripts) {

        Map<String, Object> variables = new HashMap<>();
        // Add overall variables
        for (String key : inputs.keySet()) {
            variables.put(key, inputs.get(key));
        }
        ResourceLoader loader = new DefaultResourceLoader();

        for (Script script : scripts) {
            NamespacedBinding binding = create();
            // Bind inputs.
            for (String key : variables.keySet()) {
                binding.setVariable(key, variables.get(key));
            }
            binding.startCapture();

            String filename = Strings.isNullOrEmpty(scriptDirectory) ? script.getName() : scriptDirectory
                    + File.separator + script.getName();
            try (InputStream stream = loader.getResource(filename).getInputStream()) {
                List<String> lines = IOUtils.readLines(stream);
                String content = String.join("\n", lines);

                transaction.exec(() -> execute(content, binding));
            } catch (IOException e) {
                throw Throwables.propagate(e);
            }

            // Add output variables
            if (Strings.isNullOrEmpty(script.getNamespace())) {
                for (String key : binding.getNamespacedVariables().keySet()) {
                    variables.put(key, binding.getNamespacedVariables().get(key));
                }
            } else {
                variables.put(script.getNamespace(), binding.getNamespacedVariables());
            }
        }

        return variables;
    }


    @SuppressWarnings("serial")
    private NamespacedBinding create() {
        Closure<List<Object>> table = new Closure<List<Object>>(this) {

            @SuppressWarnings("unchecked")
            @Override
            public List<Object> call(Object... args) {
                if (args == null || args.length != 3) {
                    throw new RuntimeException("The table method expects JPA entity class reference, "
                            + "list of bean properties and a closure");
                }
                return invokeWithClosure((Class<?>) args[0], (List<String>) args[1], (Closure<Void>) args[2]);
            }
        };

        Closure<Object> row = new Closure<Object>(this) {

            @Override
            public Object call(Object... args) {
                return invokeRowClosure(args);
            };
        };

        Closure<Object> find = new Closure<Object>(this) {

            @SuppressWarnings("unchecked")
            @Override
            public Object call(Object... args) {
                return invokeFindClosure((Class<? extends Serializable>) args[0], (Serializable) args[1]);
            };
        };

        Closure<Object> load = new Closure<Object>(this) {

            @Override
            public Object call(Object... args) {
                return invokeLoadClosure(args);
            };
        };

        NamespacedBinding binding = new NamespacedBinding();
        binding.setVariable("table", table);
        binding.setVariable("row", row);
        binding.setVariable("find", find);
        binding.setVariable("load", load);
        return binding;
    }


    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(String script, Binding binding) {
        GroovyShell shell = new GroovyShell(getClass().getClassLoader(), binding);
        shell.evaluate(script);
        return binding.getVariables();
    }


    private <V> List<Object> invokeWithClosure(Class<?> clz, List<String> attributes, Closure<V> callable) {
        ScriptContext context = new ScriptContext();
        context.setEntityClass(clz);
        context.setAttributes(attributes);

        scriptContextStack.push(context);

        callable.call();

        scriptContextStack.pop();
        return context.getCreatedEntities();
    }


    private Object invokeRowClosure(Object... attributeValues) {
        Serializable instance = instantiate();
        DelegatingGroovyObjectSupport<Serializable> delegate = new DelegatingGroovyObjectSupport<Serializable>(instance);

        for (int i = 0; i < scriptContextStack.peek().getAttributes().size(); i++) {
            delegate.setProperty(scriptContextStack.peek().getAttributes().get(i), attributeValues[i]);
        }
        Object entity = daoRegistry.get(instance).create(instance);
        scriptContextStack.peek().getCreatedEntities().add(entity);
        return entity;
    }


    private Object invokeFindClosure(Class<? extends Serializable> clz, Serializable id) {
        return daoRegistry.get(clz).findById(id).orElse(null);
    }


    @SuppressWarnings("unchecked")
    private Object invokeLoadClosure(Object[] args) {
        if (args.length == 0) {
            throw new IllegalArgumentException("The load() method should be called with a map of namespace and "
                    + "script names or a list of one or script names.");
        } else if (args[0] instanceof Map) {
            Map<String, String> scriptMap = (Map<String, String>) args[0];
            List<Script> scripts = new ArrayList<>();
            for (String namespace : scriptMap.keySet()) {
                scripts.add(Script.with(scriptMap.get(namespace), namespace));
            }
            return loadNamespaced(scripts);
        } else {
            // Assuming these are simply script names.
            List<String> scripts = new ArrayList<>();
            for (int i = 0; i < args.length; i++) {
                scripts.add((String) args[i]);
            }
            return load(scripts);
        }
    }


    private Serializable instantiate() {
        try {
            return (Serializable) scriptContextStack.peek().getEntityClass().newInstance();
        } catch (InstantiationException | IllegalAccessException e) {
            throw Throwables.propagate(e);
        }
    }

}