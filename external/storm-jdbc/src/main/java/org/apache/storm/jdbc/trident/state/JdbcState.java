/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.storm.jdbc.trident.state;

import backtype.storm.Config;
import backtype.storm.topology.FailedException;
import backtype.storm.tuple.Values;
import com.google.common.collect.Lists;
import org.apache.commons.lang.Validate;
import org.apache.storm.jdbc.common.Column;
import org.apache.storm.jdbc.common.JdbcClient;
import org.apache.storm.jdbc.mapper.JdbcMapper;
import org.apache.storm.jdbc.mapper.JdbcLookupMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import storm.trident.operation.TridentCollector;
import storm.trident.state.State;
import storm.trident.tuple.TridentTuple;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class JdbcState implements State {

    private static final Logger LOG = LoggerFactory.getLogger(JdbcState.class);

    private Options options;
    private JdbcClient jdbcClient;
    private Map map;

    protected JdbcState(Map map, int partitionIndex, int numPartitions, Options options) {
        this.options = options;
        this.map = map;
    }

    public static class Options implements Serializable {
        private JdbcMapper mapper;
        private JdbcLookupMapper jdbcLookupMapper;
        private String configKey;
        private String tableName;
        private String selectQuery;
        private Integer queryTimeoutSecs;

        public Options withConfigKey(String configKey) {
            this.configKey = configKey;
            return this;
        }

        public Options withTableName(String tableName) {
            this.tableName = tableName;
            return this;
        }

        public Options withMapper(JdbcMapper mapper) {
            this.mapper = mapper;
            return this;
        }

        public Options withJdbcLookupMapper(JdbcLookupMapper jdbcLookupMapper) {
            this.jdbcLookupMapper = jdbcLookupMapper;
            return this;
        }

        public Options withSelectQuery(String selectQuery) {
            this.selectQuery = selectQuery;
            return this;
        }

        public Options withQueryTimeoutSecs(int queryTimeoutSecs) {
            this.queryTimeoutSecs = queryTimeoutSecs;
            return this;
        }
    }

    protected void prepare() {
        Map<String, Object> conf = (Map<String, Object>) map.get(options.configKey);
        Validate.notEmpty(conf, "Hikari configuration not found using key '" + options.configKey + "'");

        if(options.queryTimeoutSecs == null) {
            options.queryTimeoutSecs = Integer.parseInt(map.get(Config.TOPOLOGY_MESSAGE_TIMEOUT_SECS).toString());
        }

        this.jdbcClient = new JdbcClient(conf, options.queryTimeoutSecs);
    }

    @Override
    public void beginCommit(Long aLong) {
        LOG.debug("beginCommit is noop.");
    }

    @Override
    public void commit(Long aLong) {
        LOG.debug("commit is noop.");
    }

    public void updateState(List<TridentTuple> tuples, TridentCollector collector) {
        List<List<Column>> columnsLists = new ArrayList<List<Column>>();

        for (TridentTuple tuple : tuples) {
            columnsLists.add(options.mapper.getColumns(tuple));
        }

        try {
            jdbcClient.insert(options.tableName, columnsLists);
        } catch (Exception e) {
            LOG.warn("Batch write failed but some requests might have succeeded. Triggering replay.", e);
            throw new FailedException(e);
        }
    }

    public List<List<Values>> batchRetrieve(List<TridentTuple> tridentTuples) {
        List<List<Values>> batchRetrieveResult = Lists.newArrayList();
        try {
            for (TridentTuple tuple : tridentTuples) {
                List<Column> columns = options.jdbcLookupMapper.getColumns(tuple);
                List<List<Column>> rows = jdbcClient.select(options.selectQuery, columns);
                for(List<Column> row : rows) {
                    List<Values> values = options.jdbcLookupMapper.toTuple(tuple, row);
                    batchRetrieveResult.add(values);
                }
            }
        } catch (Exception e) {
            LOG.warn("Batch get operation failed. Triggering replay.", e);
            throw new FailedException(e);
        }
        return batchRetrieveResult;
    }
}
