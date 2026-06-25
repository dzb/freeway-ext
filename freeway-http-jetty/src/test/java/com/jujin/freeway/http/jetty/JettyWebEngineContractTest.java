package com.jujin.freeway.http.jetty;

import com.jujin.freeway.commons.coercion.Coercer;
import com.jujin.freeway.commons.json.JsonCodec;
import com.jujin.freeway.commons.json.JsonCodecDefault;
import com.jujin.freeway.commons.coercion.CoercerDefault;
import com.jujin.freeway.http.*;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class JettyWebEngineContractTest {

    @Test
    void createsEngine() {
        JsonCodec jsonCodec = new JsonCodecDefault();
        Coercer coercer = new CoercerDefault();
        JettyWebEngine engine = new JettyWebEngine(jsonCodec, coercer);
        assertNotNull(engine);
    }
}
