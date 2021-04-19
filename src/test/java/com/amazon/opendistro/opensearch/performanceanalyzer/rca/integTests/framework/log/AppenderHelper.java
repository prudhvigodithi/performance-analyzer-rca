/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

/*
 * Copyright 2021 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.amazon.opendistro.opensearch.performanceanalyzer.rca.integTests.framework.log;


import java.util.Collection;
import java.util.Objects;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.builder.api.AppenderComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.api.RootLoggerComponentBuilder;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;

public class AppenderHelper {
    public static Configuration addMemoryAppenderToRootLogger() {
        Configuration oldConfiguration = LoggerContext.getContext().getConfiguration();

        ConfigurationBuilder<BuiltConfiguration> builder =
                ConfigurationBuilderFactory.newConfigurationBuilder();
        builder.setStatusLevel(Level.INFO);
        builder.setConfigurationName("RcaItLogger");
        builder.setPackages(
                "com.amazon.opendistro.opensearch.performanceanalyzer.rca.integTests.framework.log");
        RootLoggerComponentBuilder rootLogger = builder.newRootLogger(Level.INFO);

        addRcaItInMemoryAppender(builder, rootLogger);
        addConsoleAppender(builder, rootLogger);

        builder.add(rootLogger);
        Configuration configuration = builder.build();
        Configurator.reconfigure(configuration);
        return oldConfiguration;
    }

    private static void addRcaItInMemoryAppender(
            ConfigurationBuilder builder, RootLoggerComponentBuilder rootLogger) {
        AppenderComponentBuilder appenderBuilder =
                builder.newAppender(RcaItInMemoryAppender.NAME, RcaItInMemoryAppender.NAME);
        addLogPattern(builder, appenderBuilder);
        rootLogger.add(builder.newAppenderRef(RcaItInMemoryAppender.NAME));
        builder.add(appenderBuilder);
    }

    private static void addConsoleAppender(
            ConfigurationBuilder builder, RootLoggerComponentBuilder rootLogger) {
        AppenderComponentBuilder appenderBuilder =
                builder.newAppender("Console", "CONSOLE")
                        .addAttribute("target", ConsoleAppender.Target.SYSTEM_OUT);
        addLogPattern(builder, appenderBuilder);
        rootLogger.add(builder.newAppenderRef("Console"));
        builder.add(appenderBuilder);
    }

    private static void addLogPattern(
            ConfigurationBuilder builder, AppenderComponentBuilder appenderBuilder) {
        appenderBuilder.add(
                builder.newLayout("PatternLayout")
                        .addAttribute("pattern", RcaItInMemoryAppender.PATTERN));
    }

    public static void setLoggerConfiguration(Configuration configuration) {
        Objects.requireNonNull(configuration);
        Configurator.reconfigure(configuration);
    }

    public static Collection<String> getAllErrorsInLog() throws IllegalStateException {
        return RcaItInMemoryAppender.self().getAllErrors();
    }

    public static void resetErrors() {
        RcaItInMemoryAppender.self().reset();
    }
}
