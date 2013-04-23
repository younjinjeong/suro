/*
 * Copyright 2013 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.suro.server;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Singleton;
import com.google.inject.servlet.GuiceFilter;
import com.google.inject.servlet.GuiceServletContextListener;
import com.google.inject.servlet.ServletModule;
import com.sun.jersey.guice.spi.container.servlet.GuiceContainer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;

@Singleton
public class StatusServer {
    static Logger log = LoggerFactory.getLogger(StatusServer.class);

    public static ServletModule createJerseyServletModule() {
        return new ServletModule() {
            @Override
            protected void configureServlets() {
                bind(HealthCheck.class);
                bind(SinkStat.class);
                bind(GuiceContainer.class).asEagerSingleton();
                serve("/*").with(GuiceContainer.class);
            }
        };
    }

    private final Server server;
    private final ExecutorService executors = Executors.newSingleThreadExecutor();
    private final ServerConfig config;

    @Inject
    public StatusServer(ServerConfig config) {
        this.config = config;
        server = new Server(config.getStatusServerPort());
    }


    private Future statusServerStarted;
    public void start(final Injector injector) {
        ServletContextHandler sch = new ServletContextHandler(server, "/");
        sch.addEventListener(new GuiceServletContextListener() {
            @Override
            protected Injector getInjector() {
                return injector;
            }
        });
        sch.addFilter(GuiceFilter.class, "/*", null);
        sch.addServlet(DefaultServlet.class, "/");

        try {
            statusServerStarted = executors.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        log.info("StatusServer starts on the port: " + config.getStatusServerPort());
                        server.start();
                    } catch (Exception e) {
                        log.error("Exception while starting StatusServer: " + e.getMessage(), e);
                        throw new RuntimeException(e);
                    }
                }
            });
            statusServerStarted.get(config.getStartupTimeout(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(-1);
        }
    }

    public void shutdown() {
        try {
            log.info("shutting down StatusServer");
            server.stop();
        } catch (Exception e) {
            //ignore exceptions while shutdown
        }
    }
}
