package com.jujin.freeway.http.undertow;

import com.jujin.freeway.http.HttpEngine;
import com.jujin.freeway.ioc.Binder;
import com.jujin.freeway.ioc.ModuleEx;

/** IoC module that installs the Undertow HTTP engine as the primary engine. */
public final class UndertowWebEngineModule implements ModuleEx {
    @Override
    public void bind(Binder binder) {
        binder.bind(HttpEngine.class)
            .to(UndertowWebEngine.class)
            .id("undertow")
            .primary();
    }
}
