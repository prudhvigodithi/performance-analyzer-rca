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
 * Copyright 2019-2021 Amazon.com, Inc. or its affiliates. All Rights Reserved.
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

package com.amazon.opendistro.opensearch.performanceanalyzer.metrics;


import com.amazon.opendistro.opensearch.performanceanalyzer.collectors.DisksCollector;
import com.amazon.opendistro.opensearch.performanceanalyzer.collectors.GCInfoCollector;
import com.amazon.opendistro.opensearch.performanceanalyzer.collectors.HeapMetricsCollector;
import com.amazon.opendistro.opensearch.performanceanalyzer.collectors.MetricsPurgeActivity;
import com.amazon.opendistro.opensearch.performanceanalyzer.collectors.MountedPartitionMetricsCollector;
import com.amazon.opendistro.opensearch.performanceanalyzer.collectors.NetworkE2ECollector;
import com.amazon.opendistro.opensearch.performanceanalyzer.collectors.NetworkInterfaceCollector;
import com.amazon.opendistro.opensearch.performanceanalyzer.collectors.StatsCollector;
import com.amazon.opendistro.opensearch.performanceanalyzer.config.PluginSettings;
import com.amazon.opendistro.opensearch.performanceanalyzer.jvm.GCMetrics;
import com.amazon.opendistro.opensearch.performanceanalyzer.jvm.HeapMetrics;
import com.amazon.opendistro.opensearch.performanceanalyzer.jvm.ThreadList;
import com.amazon.opendistro.opensearch.performanceanalyzer.os.OSGlobals;
import com.amazon.opendistro.opensearch.performanceanalyzer.os.ThreadCPU;
import com.amazon.opendistro.opensearch.performanceanalyzer.os.ThreadDiskIO;
import com.amazon.opendistro.opensearch.performanceanalyzer.os.ThreadSched;
import java.util.HashMap;
import java.util.Map;

public class MetricsConfiguration {
    public static final int SAMPLING_INTERVAL = 5000;
    public static final int ROTATION_INTERVAL = 30000;
    public static final int STATS_ROTATION_INTERVAL = 60000;
    public static final int DELETION_INTERVAL =
            PluginSettings.instance().getMetricsDeletionInterval();

    public static class MetricConfig {
        public int samplingInterval;
        public int rotationInterval;
        public int deletionInterval;

        public MetricConfig(int samplingInterval, int rotationInterval, int deletionInterval) {
            this.samplingInterval = samplingInterval;
            this.rotationInterval = rotationInterval;
            this.deletionInterval = deletionInterval;
        }
    }

    public static final Map<Class, MetricConfig> CONFIG_MAP = new HashMap<>();
    public static final MetricConfig cdefault;

    static {
        cdefault = new MetricConfig(SAMPLING_INTERVAL, 0, 0);

        CONFIG_MAP.put(ThreadCPU.class, cdefault);
        CONFIG_MAP.put(ThreadDiskIO.class, cdefault);
        CONFIG_MAP.put(ThreadSched.class, cdefault);
        CONFIG_MAP.put(ThreadList.class, cdefault);
        CONFIG_MAP.put(GCMetrics.class, cdefault);
        CONFIG_MAP.put(HeapMetrics.class, cdefault);
        CONFIG_MAP.put(NetworkE2ECollector.class, cdefault);
        CONFIG_MAP.put(NetworkInterfaceCollector.class, cdefault);
        CONFIG_MAP.put(OSGlobals.class, cdefault);
        CONFIG_MAP.put(PerformanceAnalyzerMetrics.class, new MetricConfig(0, ROTATION_INTERVAL, 0));
        CONFIG_MAP.put(
                MetricsPurgeActivity.class,
                new MetricConfig(ROTATION_INTERVAL, 0, DELETION_INTERVAL));
        CONFIG_MAP.put(StatsCollector.class, new MetricConfig(STATS_ROTATION_INTERVAL, 0, 0));
        CONFIG_MAP.put(DisksCollector.class, cdefault);
        CONFIG_MAP.put(HeapMetricsCollector.class, cdefault);
        CONFIG_MAP.put(GCInfoCollector.class, cdefault);
        CONFIG_MAP.put(MountedPartitionMetricsCollector.class, cdefault);
    }
}
