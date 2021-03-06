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
package org.lilyproject.testfw;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.zookeeper.*;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

import static org.apache.zookeeper.ZooKeeper.States.CONNECTED;

/**
 * Provides access to HBase, either by starting an embedded HBase or by connecting to a running HBase.
 *
 * <p>This is intended for usage in test cases.
 *
 * <p><b>VERY VERY IMPORTANT</b>: when connecting to an existing HBase, this class will DELETE ALL ROWS
 * FROM ALL TABLES!
 */
public class HBaseProxy {
    private static Mode MODE;
    private static Configuration CONF;
    private static HBaseTestingUtility TEST_UTIL;

    private enum Mode { EMBED, CONNECT }
    private static String HBASE_MODE_PROP_NAME = "lily.test.hbase";

    private static Set<String> RETAIN_TABLES = new HashSet<String>();
    static {
        RETAIN_TABLES.add("indexmeta");
    }

    // For some tables, we exploit the timestamp dimension by using custom timestamps, which
    // for certain tests (where the same row key and timestamp is reused) cause problems, due
    // to the use of non-increasing timestamps, see also http://markmail.org/message/xskvbzhrvkv7skxz
    // or http://search-hadoop.com/m/rNnhN15Xecu
    // For these tables, we need to flush and compact them, and wait for this to complete,
    // before continuing.
    // This map contains as key the name of the table and as value the name of the/a column family where versions
    // are used in this way.
    private static Map<String, byte[]> EXPLOIT_TIMESTAMP_TABLES = new HashMap<String, byte[]>();
    static {
        EXPLOIT_TIMESTAMP_TABLES.put("record", Bytes.toBytes("data"));
        EXPLOIT_TIMESTAMP_TABLES.put("type", Bytes.toBytes("fieldtype-entry"));
    }

    public void start() throws Exception {
        String hbaseModeProp = System.getProperty(HBASE_MODE_PROP_NAME);
        if (hbaseModeProp == null || hbaseModeProp.equals("") || hbaseModeProp.equals("embed")) {
            MODE = Mode.EMBED;
        } else if (hbaseModeProp.equals("connect")) {
            MODE = Mode.CONNECT;
        } else {
            throw new RuntimeException("Unexpected value for " + HBASE_MODE_PROP_NAME + ": " + hbaseModeProp);
        }

        System.out.println("HBase usage mode: " + MODE);

        CONF = HBaseConfiguration.create();

        switch (MODE) {
            case EMBED:
                addHBaseTestProps(CONF);
                addUserProps(CONF);
                System.clearProperty(HBaseTestingUtility.TEST_DIRECTORY_KEY);
                TEST_UTIL = new HBaseTestingUtility(CONF);
                TEST_UTIL.startMiniCluster(1);
                CONF = TEST_UTIL.getConfiguration();
                break;
            case CONNECT:
                CONF.set("hbase.zookeeper.quorum", "localhost");
                CONF.set("hbase.zookeeper.property.clientPort", "2181"); // matches HBaseRunner
                addUserProps(CONF);
                cleanZooKeeper();
                cleanTables();
                break;
            default:
                throw new RuntimeException("Unexpected mode: " + MODE);
        }
    }

    public String getZkConnectString() {
        return CONF.get("hbase.zookeeper.quorum") + ":" + CONF.get("hbase.zookeeper.property.clientPort");
    }
    
    /**
     * Adds all system property prefixed with "lily.test.hbase." to the HBase configuration.
     */
    private void addUserProps(Configuration conf) {
        Properties sysProps = System.getProperties();
        for (Map.Entry<Object, Object> entry : sysProps.entrySet()) {
            String name = entry.getKey().toString();
            if (name.startsWith("lily.test.hbase.")) {
                String hbasePropName = name.substring("lily.test.".length());
                conf.set(hbasePropName, entry.getValue().toString());
            }
        }
    }

    protected static void addHBaseTestProps(Configuration conf) {
        // The following properties are from HBase's src/test/resources/hbase-site.xml
        conf.set("hbase.regionserver.msginterval", "1000");
        conf.set("hbase.client.pause", "5000");
        conf.set("hbase.client.retries.number", "4");
        conf.set("hbase.master.meta.thread.rescanfrequency", "10000");
        conf.set("hbase.server.thread.wakefrequency", "1000");
        conf.set("hbase.regionserver.handler.count", "5");
        conf.set("hbase.master.info.port", "-1");
        conf.set("hbase.regionserver.info.port", "-1");
        conf.set("hbase.regionserver.info.port.auto", "true");
        conf.set("hbase.master.lease.thread.wakefrequency", "3000");
        conf.set("hbase.regionserver.optionalcacheflushinterval", "1000");
        conf.set("hbase.regionserver.safemode", "false");
    }

    public void stop() throws Exception {
        // Close connections with HBase and HBase's ZooKeeper handles
        //HConnectionManager.deleteConnectionInfo(CONF, true);
        HConnectionManager.deleteAllConnections(true);

        if (MODE == Mode.EMBED) {
            // Since HBase mini cluster shutdown has a tendency of sometimes failing (hanging waiting on master
            // to end), add a protection for this so that we do not run indefinitely. Especially important not to
            // annoy the other projects on our Hudson server.
            Thread stopHBaseThread = new Thread() {
                @Override
                public void run() {
                    try {
                        TEST_UTIL.shutdownMiniCluster();
                        TEST_UTIL = null;
                    } catch (IOException e) {
                        System.out.println("Error shutting down mini cluster.");
                        e.printStackTrace();
                    }
                }
            };
            stopHBaseThread.start();
            stopHBaseThread.join(60000);
            if (stopHBaseThread.isAlive()) {
                System.err.println("Unable to stop embedded mini cluster within predetermined timeout.");
                System.err.println("Dumping stack for future investigation.");
                Thread.dumpStack();
                System.out.println("Will now try to interrupt the mini-cluster-stop-thread and give it some more time to end.");
                stopHBaseThread.interrupt();
                stopHBaseThread.join(20000);
                throw new Exception("Failed to stop the mini cluster within the predetermined timeout.");
            }
        }
        CONF = null;
    }

    public Configuration getConf() {
        return CONF;
    }

    public FileSystem getBlobFS() throws IOException, URISyntaxException {
        if (MODE == Mode.EMBED) {
            return TEST_UTIL.getDFSCluster().getFileSystem();
        } else {
            String dfsUri = System.getProperty("lily.test.dfs");

            if (dfsUri == null) {
                dfsUri = "hdfs://localhost:9000";
            }

            return FileSystem.get(new URI(dfsUri), getConf());
        }
    }

    public void cleanZooKeeper() throws Exception {
        int sessionTimeout = 10000;

        ZooKeeper zk = new ZooKeeper(getZkConnectString(), sessionTimeout, new Watcher() {
            public void process(WatchedEvent event) {
                if (event.getState() == Watcher.Event.KeeperState.Disconnected) {
                    System.err.println("ZooKeeper Disconnected.");
                } else if (event.getState() == Event.KeeperState.Expired) {
                    System.err.println("ZooKeeper session expired.");
                }
            }
        });

        long waitUntil = System.currentTimeMillis() + sessionTimeout;
        while (zk.getState() != CONNECTED && waitUntil > System.currentTimeMillis()) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                break;
            }
        }

        if (zk.getState() != CONNECTED) {
            throw new RuntimeException("Failed to connect to ZK within " + sessionTimeout + "ms.");
        }

        if (zk.exists("/lily", false) != null) {
            System.out.println("----------------- Clearing '/lily' node in ZooKeeper -------------------");
            deleteChildren("/lily", zk);
            zk.delete("/lily", -1);
            System.out.println("------------------------------------------------------------------------");
        }

        zk.close();
    }

    private void deleteChildren(String path, ZooKeeper zk) throws InterruptedException, KeeperException {
        List<String> children = zk.getChildren(path, false);
        for (String child : children) {
            String childPath = path + "/" + child;
            deleteChildren(childPath, zk);
            System.out.println("Deleting " + path);
            zk.delete(childPath, -1);
        }
    }

    public void cleanTables() throws Exception {
        System.out.println("------------------------ Resetting HBase tables ------------------------");

        StringBuilder truncateReport = new StringBuilder();
        StringBuilder retainReport = new StringBuilder();

        HBaseAdmin admin = new HBaseAdmin(getConf());
        HTableDescriptor[] tables = admin.listTables();
        System.out.println("Found tables: " + tables.length);

        Set<String> exploitTimestampTables = new HashSet<String>();

        for (HTableDescriptor table : tables) {
            if (RETAIN_TABLES.contains(table.getNameAsString())) {
                if (retainReport.length() > 0)
                    retainReport.append(", ");
                retainReport.append(table.getNameAsString());
                continue;
            }

            HTable htable = new HTable(getConf(), table.getName());

            if (EXPLOIT_TIMESTAMP_TABLES.containsKey(table.getNameAsString())) {
                insertTimestampTableTestRecord(table.getNameAsString(), htable);
                exploitTimestampTables.add(table.getNameAsString());
            }

            Scan scan = new Scan();
            ResultScanner scanner = htable.getScanner(scan);
            Result[] results;
            int totalCount = 0;

            while ((results = scanner.next(1000)).length > 0) {
                List<Delete> deletes = new ArrayList<Delete>(results.length);
                for (Result result : results) {
                    deletes.add(new Delete(result.getRow()));
                }
                totalCount += deletes.size();
                htable.delete(deletes);
            }

            if (truncateReport.length() > 0)
                truncateReport.append(", ");
            truncateReport.append(table.getNameAsString()).append(" (").append(totalCount).append(")");

            scanner.close();
            htable.close();

            if (EXPLOIT_TIMESTAMP_TABLES.containsKey(table.getNameAsString())) {
                admin.flush(table.getName());
                admin.majorCompact(table.getName());
            }
        }

        truncateReport.insert(0, "Truncated the following tables: ");
        retainReport.insert(0, "Did not truncate the following tables: ");

        System.out.println(truncateReport);
        System.out.println(retainReport);

        waitForTimestampTables(exploitTimestampTables);

        System.out.println("------------------------------------------------------------------------");

    }

    private void insertTimestampTableTestRecord(String tableName, HTable htable) throws IOException {
        byte[] tmpRowKey = Bytes.toBytes("HBaseProxyDummyRow");
        byte[] CF = EXPLOIT_TIMESTAMP_TABLES.get(tableName);
        byte[] COL = Bytes.toBytes("DummyColumn");
        Put put = new Put(tmpRowKey);
        // put a value with a fixed timestamp
        put.add(CF, COL, 1, new byte[] { 0 });

        htable.put(put);
    }

    private void waitForTimestampTables(Set<String> tables) throws IOException, InterruptedException {
        for (String tableName : tables) {

            HTable htable = new HTable(CONF, tableName);

            byte[] CF = EXPLOIT_TIMESTAMP_TABLES.get(tableName);
            byte[] tmpRowKey = waitForCompact(tableName, CF);

            // Delete our dummy row again
            htable.delete(new Delete(tmpRowKey));
        }
    }

    private byte[] waitForCompact(String tableName, byte[] CF) throws IOException, InterruptedException {
        byte[] tmpRowKey = Bytes.toBytes("HBaseProxyDummyRow");
        byte[] COL = Bytes.toBytes("DummyColumn");
        HTable htable = new HTable(CONF, tableName);

        byte[] value = null;
        while (value == null) {
            Put put = new Put(tmpRowKey);
            put.add(CF, COL, 1, new byte[] { 0 });
            htable.put(put);

            Get get = new Get(tmpRowKey);
            Result result = htable.get(get);
            value = result.getValue(CF, COL);
            if (value == null) {
                // If the value is null, it is because the delete marker has not yet been flushed/compacted away
                System.out.println("Waiting for flush/compact of " + tableName + " to complete");
                Thread.sleep(100);
            }
        }
        return tmpRowKey;
    }

    /** Force a major compaction and wait for it to finish.
     *  This method can be used in a test to avoid issue HBASE-2256 after performing a delete operation 
     *  Uses same principle as {@link #cleanTables()}
     */ 
    public void majorCompact(String tableName, String[] columnFamilies) throws Exception {
        byte[] tmpRowKey = Bytes.toBytes("HBaseProxyDummyRow");
        byte[] COL = Bytes.toBytes("DummyColumn");
        HBaseAdmin admin = new HBaseAdmin(getConf());
        HTable htable = new HTable(CONF, tableName);
        
        // Write a dummy row
        for (String columnFamily : columnFamilies) {
            byte[] CF = Bytes.toBytes(columnFamily);
            Put put = new Put(tmpRowKey);
            put.add(CF, COL, 1, new byte[] { 0 });
            htable.put(put);
            // Delete the value again
            Delete delete = new Delete(tmpRowKey);
            delete.deleteColumn(CF, COL);
            htable.delete(delete);
        }
        
        // Perform major compaction
        admin.flush(tableName);
        admin.majorCompact(tableName);
        
        // Wait for compact to finish
        for (String columnFamily : columnFamilies) {
            byte[] CF = Bytes.toBytes(columnFamily);
            waitForCompact(tableName, CF);
        }
    }
}
