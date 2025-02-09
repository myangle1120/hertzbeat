/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hertzbeat.warehouse.store;

import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.apache.hertzbeat.common.constants.CommonConstants;
import org.apache.hertzbeat.common.entity.message.CollectRep;
import org.apache.hertzbeat.common.queue.CommonDataQueue;
import org.apache.hertzbeat.plugin.PostCollectPlugin;
import org.apache.hertzbeat.plugin.runner.PluginRunner;
import org.apache.hertzbeat.warehouse.WarehouseWorkerPool;
import org.apache.hertzbeat.warehouse.store.history.HistoryDataWriter;
import org.apache.hertzbeat.warehouse.store.realtime.RealTimeDataWriter;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * dispatch storage metrics data
 */
@Slf4j
@Component
public class DataStorageDispatch {

    private final CommonDataQueue commonDataQueue;
    private final WarehouseWorkerPool workerPool;
    private final JdbcTemplate jdbcTemplate;
    private final RealTimeDataWriter realTimeDataWriter;
    private final Optional<HistoryDataWriter> historyDataWriter;
    private final PluginRunner pluginRunner;

    public DataStorageDispatch(CommonDataQueue commonDataQueue,
                               WarehouseWorkerPool workerPool,
                               JdbcTemplate jdbcTemplate,
                               Optional<HistoryDataWriter> historyDataWriter,
                               RealTimeDataWriter realTimeDataWriter,
                               PluginRunner pluginRunner) {
        this.commonDataQueue = commonDataQueue;
        this.workerPool = workerPool;
        this.jdbcTemplate = jdbcTemplate;
        this.realTimeDataWriter = realTimeDataWriter;
        this.historyDataWriter = historyDataWriter;
        this.pluginRunner = pluginRunner;
        startPersistentDataStorage();
    }

    protected void startPersistentDataStorage() {
        Runnable runnable = () -> {
            Thread.currentThread().setName("warehouse-persistent-data-storage");
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    CollectRep.MetricsData metricsData = commonDataQueue.pollMetricsDataToStorage();
                    if (metricsData == null) {
                        continue;
                    }
                    calculateMonitorStatus(metricsData);
                    historyDataWriter.ifPresent(dataWriter -> dataWriter.saveData(metricsData));
                    pluginRunner.pluginExecute(PostCollectPlugin.class, ((postCollectPlugin, pluginContext) -> postCollectPlugin.execute(metricsData, pluginContext)));
                    realTimeDataWriter.saveData(metricsData);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                }
            }
        };
        workerPool.executeJob(runnable);
    }
    
    protected void calculateMonitorStatus(CollectRep.MetricsData metricsData) {
        if (metricsData.getPriority() == 0) {
            long id = metricsData.getId();
            CollectRep.Code code = metricsData.getCode();
            // query current status
            String queryStatusSql = "SELECT status FROM hzb_monitor WHERE id = ?";
            try {
                int currentStatus = jdbcTemplate.queryForObject(queryStatusSql, Integer.class, id);
                if (code == CollectRep.Code.SUCCESS && currentStatus == CommonConstants.MONITOR_DOWN_CODE) {
                    // if collect success and current status is DOWN, update to UP
                    String sql = "UPDATE hzb_monitor SET status = ? WHERE id = ?";
                    jdbcTemplate.update(sql, CommonConstants.MONITOR_UP_CODE, id);
                } else if (code != CollectRep.Code.SUCCESS && currentStatus == CommonConstants.MONITOR_UP_CODE) {
                    // if collect failed and current status is UP, update to DOWN
                    String sql = "UPDATE hzb_monitor SET status = ? WHERE id = ?";
                    jdbcTemplate.update(sql, CommonConstants.MONITOR_DOWN_CODE, id);
                }
            } catch (EmptyResultDataAccessException ignored) {
                // when query currentStatus result is null
            }
        }
    }


}
