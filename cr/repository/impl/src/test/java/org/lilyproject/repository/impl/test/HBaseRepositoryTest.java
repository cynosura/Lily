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
package org.lilyproject.repository.impl.test;


import static org.junit.Assert.assertEquals;

import org.apache.hadoop.fs.Path;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.lilyproject.repository.api.BlobStoreAccessFactory;
import org.lilyproject.repository.api.Record;
import org.lilyproject.repository.api.TypeManager;
import org.lilyproject.repository.impl.DFSBlobStoreAccess;
import org.lilyproject.repository.impl.HBaseRepository;
import org.lilyproject.repository.impl.HBaseTypeManager;
import org.lilyproject.repository.impl.SizeBasedBlobStoreAccessFactory;
import org.lilyproject.rowlog.api.RowLogMessageListenerMapping;
import org.lilyproject.rowlog.api.RowLogSubscription.Type;
import org.lilyproject.testfw.TestHelper;
import org.lilyproject.util.hbase.HBaseTableFactoryImpl;
import org.lilyproject.util.io.Closer;
import org.lilyproject.util.zookeeper.ZkUtil;

public class HBaseRepositoryTest extends AbstractRepositoryTest {

    private static BlobStoreAccessFactory blobStoreAccessFactory;
    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        TestHelper.setupLogging();
        HBASE_PROXY.start();
        configuration = HBASE_PROXY.getConf();
        zooKeeper = ZkUtil.connect(HBASE_PROXY.getZkConnectString(), 10000);
        hbaseTableFactory = new HBaseTableFactoryImpl(HBASE_PROXY.getConf(), null, null);
        setupRowLogConfigurationManager(zooKeeper);
        typeManager = new HBaseTypeManager(idGenerator, configuration, zooKeeper, hbaseTableFactory);
        DFSBlobStoreAccess dfsBlobStoreAccess = new DFSBlobStoreAccess(HBASE_PROXY.getBlobFS(), new Path("/lily/blobs"));
        blobStoreAccessFactory = new SizeBasedBlobStoreAccessFactory(dfsBlobStoreAccess);
        setupWal();
        repository = new HBaseRepository(typeManager, idGenerator, blobStoreAccessFactory, wal, configuration, hbaseTableFactory);
        setupTypes();
        setupMessageQueue();
        setupMessageQueueProcessor();
    }

    @AfterClass
    public static void tearDownAfterClass() throws Exception {
        messageQueueProcessor.stop();
        Closer.close(rowLogConfigurationManager);
        Closer.close(typeManager);
        Closer.close(repository);
        Closer.close(zooKeeper);
        HBASE_PROXY.stop();
    }
    
    @Test
    public void testFieldTypeCacheInitialization() throws Exception {
        TypeManager newTypeManager = new HBaseTypeManager(idGenerator, HBASE_PROXY.getConf(), zooKeeper, hbaseTableFactory);
        assertEquals(fieldType1, newTypeManager.getFieldTypeByName(fieldType1.getName()));
        Closer.close(newTypeManager);
    }
    
    @Test
    public void testUpdateProcessesRemainingMessages() throws Exception {
        HBaseRepositoryTestConsumer.reset();
        RowLogMessageListenerMapping.INSTANCE.put("TestSubscription", new HBaseRepositoryTestConsumer());
        rowLogConfigurationManager.addSubscription("WAL", "TestSubscription", Type.VM, 3, 2);
        
        Record record = repository.newRecord();
        record.setRecordType(recordType1.getName(), recordType1.getVersion());
        record.setField(fieldType1.getName(), "value1");
        record = repository.create(record);
        record.setField(fieldType1.getName(), "value2");
        record = repository.update(record);

        assertEquals("value2", record.getField(fieldType1.getName()));

        assertEquals(record, repository.read(record.getId()));
        rowLogConfigurationManager.removeSubscription("WAL", "TestSubscription");
        RowLogMessageListenerMapping.INSTANCE.remove("TestSubscription");
    }
    
    
}
