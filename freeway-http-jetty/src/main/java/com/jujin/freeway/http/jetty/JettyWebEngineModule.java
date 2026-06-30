package com.jujin.freeway.http.jetty;

import com.jujin.freeway.http.HttpEngine;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;

public final class JettyWebEngineModule implements ModuleEx{
    @Override
    public void bind(Binder binder) {
        binder.bind(HttpEngine.class)
            .to(JettyWebEngine.class)
            .id("jetty")
            .primary();
    }
}
