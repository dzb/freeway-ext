package com.jujin.freeway.fleet.server;

import com.jujin.freeway.boot.AppRuntime;
import com.jujin.freeway.boot.FreewayApp;
import com.jujin.freeway.cloud.health.HealthModule;
import com.jujin.freeway.cloud.observe.ObserveModule;
import com.jujin.freeway.http.HttpModule;
import java.util.concurrent.CountDownLatch;

/**
 * Entry point of the standalone Fleet Server application.
 *
 * <p>It is intentionally the only application-style entry point: the server distribution ships this
 * class, an embedded H2 store and the Fleet surface in one executable jar. A deployment does not
 * need to compose modules by hand.
 */
public final class FleetServerMain {
  private FleetServerMain() {}

  public static void main(String[] args) throws InterruptedException {
    AppRuntime app =
        FreewayApp.create(
                new HttpModule(), new HealthModule(), new ObserveModule(), new FleetServerModule())
            .name("fleet-server")
            .args(args)
            .start();
    int port = app.get(com.jujin.freeway.http.HttpServer.class).port();
    System.out.println("Fleet Server started.");
    System.out.println("Dashboard: http://127.0.0.1:" + port + "/fleet/ui");
    new CountDownLatch(1).await();
  }
}
