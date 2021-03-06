/*
 * Copyright 2010 Outerthought bvba
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.lilyproject.indexer.model.impl;

import net.iharder.Base64;
import org.codehaus.jackson.node.ArrayNode;
import org.codehaus.jackson.node.JsonNodeFactory;
import org.codehaus.jackson.node.ObjectNode;
import org.lilyproject.indexer.model.api.*;
import org.lilyproject.util.json.JsonFormat;
import org.lilyproject.util.json.JsonUtil;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class IndexDefinitionConverter {
    public static IndexDefinitionConverter INSTANCE = new IndexDefinitionConverter();

    public void fromJsonBytes(byte[] json, IndexDefinitionImpl index) {
        ObjectNode node;
        try {
            node = (ObjectNode)JsonFormat.deserialize(new ByteArrayInputStream(json));
        } catch (IOException e) {
            throw new RuntimeException("Error parsing index definition JSON.", e);
        }
        fromJson(node, index);
    }

    public void fromJson(ObjectNode node, IndexDefinitionImpl index) {
        IndexGeneralState state = IndexGeneralState.valueOf(JsonUtil.getString(node, "generalState"));
        IndexUpdateState updateState = IndexUpdateState.valueOf(JsonUtil.getString(node, "updateState"));
        IndexBatchBuildState buildState = IndexBatchBuildState.valueOf(JsonUtil.getString(node, "batchBuildState"));

        String queueSubscriptionId = JsonUtil.getString(node, "queueSubscriptionId", null);

        byte[] configuration;
        try {
            String configurationAsString = JsonUtil.getString(node, "configuration");
            configuration = Base64.decode(configurationAsString);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        byte[] shardingConfiguration = null;
        if (node.get("shardingConfiguration") != null) {
            String shardingConfAsString = JsonUtil.getString(node, "shardingConfiguration");
            try {
                shardingConfiguration = Base64.decode(shardingConfAsString);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        Map<String, String> solrShards = new HashMap<String, String>();
        ArrayNode shardsArray = JsonUtil.getArray(node, "solrShards");
        for (int i = 0; i < shardsArray.size(); i++) {
            ObjectNode shardNode = (ObjectNode)shardsArray.get(i);
            String shardName = JsonUtil.getString(shardNode, "name");
            String address = JsonUtil.getString(shardNode, "address");
            solrShards.put(shardName, address);
        }

        ActiveBatchBuildInfo activeBatchBuild = null;
        if (node.get("activeBatchBuild") != null) {
            ObjectNode buildNode = JsonUtil.getObject(node, "activeBatchBuild");
            activeBatchBuild = new ActiveBatchBuildInfo();
            activeBatchBuild.setJobId(JsonUtil.getString(buildNode, "jobId"));
            activeBatchBuild.setSubmitTime(JsonUtil.getLong(buildNode, "submitTime"));
            activeBatchBuild.setTrackingUrl(JsonUtil.getString(buildNode, "trackingUrl", null));
        }

        BatchBuildInfo lastBatchBuild = null;
        if (node.get("lastBatchBuild") != null) {
            ObjectNode buildNode = JsonUtil.getObject(node, "lastBatchBuild");
            lastBatchBuild = new BatchBuildInfo();
            lastBatchBuild.setJobId(JsonUtil.getString(buildNode, "jobId"));
            lastBatchBuild.setSubmitTime(JsonUtil.getLong(buildNode, "submitTime"));
            lastBatchBuild.setSuccess(JsonUtil.getBoolean(buildNode, "success"));
            lastBatchBuild.setJobState(JsonUtil.getString(buildNode, "jobState"));
            lastBatchBuild.setTrackingUrl(JsonUtil.getString(buildNode, "trackingUrl", null));
            ObjectNode countersNode = JsonUtil.getObject(buildNode, "counters");
            Iterator<String> it = countersNode.getFieldNames();
            while (it.hasNext()) {
                String key = it.next();
                long value = JsonUtil.getLong(countersNode, key);
                lastBatchBuild.addCounter(key, value);
            }
        }

        index.setGeneralState(state);
        index.setUpdateState(updateState);
        index.setBatchBuildState(buildState);
        index.setQueueSubscriptionId(queueSubscriptionId);
        index.setConfiguration(configuration);
        index.setSolrShards(solrShards);
        index.setShardingConfiguration(shardingConfiguration);
        index.setActiveBatchBuildInfo(activeBatchBuild);
        index.setLastBatchBuildInfo(lastBatchBuild);
    }

    public byte[] toJsonBytes(IndexDefinition index) {
        try {
            return JsonFormat.serializeAsBytes(toJson(index));
        } catch (IOException e) {
            throw new RuntimeException("Error serializing index definition to JSON.", e);
        }
    }

    public ObjectNode toJson(IndexDefinition index) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();

        node.put("generalState", index.getGeneralState().toString());
        node.put("batchBuildState", index.getBatchBuildState().toString());
        node.put("updateState", index.getUpdateState().toString());

        if (index.getQueueSubscriptionId() != null)
            node.put("queueSubscriptionId", index.getQueueSubscriptionId());

        String configurationAsString;
        try {
            configurationAsString = Base64.encodeBytes(index.getConfiguration(), Base64.GZIP);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        node.put("configuration", configurationAsString);

        if (index.getShardingConfiguration() != null) {
            String shardingConfAsString;
            try {
                shardingConfAsString = Base64.encodeBytes(index.getShardingConfiguration(), Base64.GZIP);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }

            node.put("shardingConfiguration", shardingConfAsString);
        }

        ArrayNode shardsNode = node.putArray("solrShards");
        for (Map.Entry<String, String> shard : index.getSolrShards().entrySet()) {
            ObjectNode shardNode = shardsNode.addObject();
            shardNode.put("name", shard.getKey());
            shardNode.put("address", shard.getValue());
        }

        if (index.getActiveBatchBuildInfo() != null) {
            ActiveBatchBuildInfo buildInfo = index.getActiveBatchBuildInfo();
            ObjectNode buildNode = node.putObject("activeBatchBuild");
            buildNode.put("jobId", buildInfo.getJobId());
            buildNode.put("submitTime", buildInfo.getSubmitTime());
            buildNode.put("trackingUrl", buildInfo.getTrackingUrl());
        }

        if (index.getLastBatchBuildInfo() != null) {
            BatchBuildInfo buildInfo = index.getLastBatchBuildInfo();
            ObjectNode buildNode = node.putObject("lastBatchBuild");
            buildNode.put("jobId", buildInfo.getJobId());
            buildNode.put("submitTime", buildInfo.getSubmitTime());
            buildNode.put("success", buildInfo.getSuccess());
            buildNode.put("jobState", buildInfo.getJobState());
            buildNode.put("trackingUrl", buildInfo.getTrackingUrl());
            ObjectNode countersNode = buildNode.putObject("counters");
            for (Map.Entry<String, Long> counter : buildInfo.getCounters().entrySet()) {
                countersNode.put(counter.getKey(), counter.getValue());
            }
        }

        return node;
    }
}
