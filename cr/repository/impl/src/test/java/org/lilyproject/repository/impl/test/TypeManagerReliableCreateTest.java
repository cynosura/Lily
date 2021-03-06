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


import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.UUID;

import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.util.Bytes;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.lilyproject.repository.api.FieldType;
import org.lilyproject.repository.api.FieldTypeExistsException;
import org.lilyproject.repository.api.FieldTypeNotFoundException;
import org.lilyproject.repository.api.QName;
import org.lilyproject.repository.api.RecordType;
import org.lilyproject.repository.api.RecordTypeExistsException;
import org.lilyproject.repository.api.RecordTypeNotFoundException;
import org.lilyproject.repository.api.Scope;
import org.lilyproject.repository.api.TypeException;
import org.lilyproject.repository.api.TypeManager;
import org.lilyproject.repository.api.ValueType;
import org.lilyproject.repository.impl.HBaseTypeManager;
import org.lilyproject.repository.impl.IdGeneratorImpl;
import org.lilyproject.testfw.HBaseProxy;
import org.lilyproject.testfw.TestHelper;
import org.lilyproject.util.hbase.HBaseTableFactory;
import org.lilyproject.util.hbase.HBaseTableFactoryImpl;
import org.lilyproject.util.hbase.LocalHTable;
import org.lilyproject.util.io.Closer;
import org.lilyproject.util.zookeeper.ZkUtil;
import org.lilyproject.util.zookeeper.ZooKeeperItf;

public class TypeManagerReliableCreateTest {

    private final static HBaseProxy HBASE_PROXY = new HBaseProxy();
    private static final byte[] DATA_COLUMN_FAMILY = Bytes.toBytes("data");
    private static final byte[] CONCURRENT_COUNTER_COLUMN_NAME = Bytes.toBytes("cc");
    private static ValueType valueType;
    private static TypeManager basicTypeManager;
    private static ZooKeeperItf zooKeeper;
    private static HBaseTableFactory hbaseTableFactory;

    @BeforeClass
    public static void setUpBeforeClass() throws Exception {
        TestHelper.setupLogging();
        HBASE_PROXY.start();
        zooKeeper = ZkUtil.connect(HBASE_PROXY.getZkConnectString(), 10000);
        hbaseTableFactory = new HBaseTableFactoryImpl(HBASE_PROXY.getConf(), null, null);
        basicTypeManager = new HBaseTypeManager(new IdGeneratorImpl(), HBASE_PROXY.getConf(), zooKeeper, hbaseTableFactory);
        valueType = basicTypeManager.getValueType("LONG", false, false);
    }
    
    @AfterClass
    public static void tearDownAfterClass() throws Exception {
        Closer.close(basicTypeManager);
        Closer.close(zooKeeper);
        HBASE_PROXY.stop();
    }


    @Before
    public void setUp() throws Exception {
    }

    @After
    public void tearDown() throws Exception {
        HBASE_PROXY.cleanTables();
    }

    @Test
    public void testConcurrentRecordCreate() throws Exception {
        final HTableInterface typeTable = new LocalHTable(HBASE_PROXY.getConf(), Bytes.toBytes("type")) {
            @Override
            public long incrementColumnValue(byte[] row, byte[] family, byte[] qualifier, long amount)
                    throws IOException {
                long incrementColumnValue = super.incrementColumnValue(row, family, qualifier, amount);
                try {
                    basicTypeManager.createRecordType(basicTypeManager.newRecordType(new QName("NS", "name")));
                } catch (Exception e) {
                    e.printStackTrace();
                    fail();
                } 
                return incrementColumnValue;
            }
        };
        
        TypeManager typeManager = new HBaseTypeManager(new IdGeneratorImpl(), HBASE_PROXY.getConf(), zooKeeper, hbaseTableFactory) {
            @Override
            protected HTableInterface getTypeTable() {
                return typeTable;
            }
        };
        try {
            typeManager.createRecordType(typeManager.newRecordType(new QName("NS", "name")));
            fail();
        } catch (TypeException expected) {
        } catch (RecordTypeExistsException expected) {
            // This will be thrown when the cache of the typeManager was updated as a consequence of the update on basicTypeManager
            // Through ZooKeeper the cache will have been marked as invalidated
        }
        typeManager.close();
    }
    
    @Test
    public void testConcurrentRecordUpdate() throws Exception {
        final HTableInterface typeTable = new LocalHTable(HBASE_PROXY.getConf(), Bytes.toBytes("type")) {
            @Override
            public long incrementColumnValue(byte[] row, byte[] family, byte[] qualifier, long amount)
                    throws IOException {
                long incrementColumnValue = super.incrementColumnValue(row, family, qualifier, amount);
                try {
                    RecordType recordType = basicTypeManager.getRecordTypeByName(new QName("NS", "name1"), null);
                    recordType.setName(new QName("NS", "name2"));
                    basicTypeManager.updateRecordType(recordType);
                } catch (Exception e) {
                    e.printStackTrace();
                    fail();
                } 
                return incrementColumnValue;
            }
        };
        
        TypeManager typeManager = new HBaseTypeManager(new IdGeneratorImpl(), HBASE_PROXY.getConf(), zooKeeper, hbaseTableFactory) {
            @Override
            protected HTableInterface getTypeTable() {
                return typeTable;
            }
        };
        basicTypeManager.createRecordType(typeManager.newRecordType(new QName("NS", "name1")));
        try {
            typeManager.createRecordType(typeManager.newRecordType(new QName("NS", "name2")));
            fail();
        } catch (TypeException expected) {
        } catch (RecordTypeExistsException expected) {
            // This will be thrown when the cache of the typeManager was updated as a consequence of the update on basicTypeManager
            // Through ZooKeeper the cache will have been marked as invalidated
        }
        typeManager.close();
    }

    @Test
    public void testConcurrentFieldCreate() throws Exception {
        final HTableInterface typeTable = new LocalHTable(HBASE_PROXY.getConf(), Bytes.toBytes("type")) {
            @Override
            public long incrementColumnValue(byte[] row, byte[] family, byte[] qualifier, long amount)
                    throws IOException {
                long incrementColumnValue = super.incrementColumnValue(row, family, qualifier, amount);
                try {
                    basicTypeManager.createFieldType(basicTypeManager.newFieldType(valueType, new QName("NS", "name"), Scope.VERSIONED));
                } catch (Exception e) {
                    e.printStackTrace();
                    fail();
                } 
                return incrementColumnValue;
            }
        };
        
        TypeManager typeManager = new HBaseTypeManager(new IdGeneratorImpl(), HBASE_PROXY.getConf(), zooKeeper, hbaseTableFactory) {
            @Override
            protected HTableInterface getTypeTable() {
                return typeTable;
            }
        };
        try {
            typeManager.createFieldType(typeManager.newFieldType(valueType, new QName("NS", "name"), Scope.VERSIONED));
            fail();
        } catch (TypeException expected) {
        } catch (FieldTypeExistsException expected) {
            // This will be thrown when the cache of the typeManager was updated as a consequence of the update on basicTypeManager
            // Through ZooKeeper the cache will have been marked as invalidated
        }
        typeManager.close();
    }
    
    @Test
    public void testConcurrentFieldUpdate() throws Exception {
        final HTableInterface typeTable = new LocalHTable(HBASE_PROXY.getConf(), Bytes.toBytes("type")) {
            @Override
            public long incrementColumnValue(byte[] row, byte[] family, byte[] qualifier, long amount)
                    throws IOException {
                long incrementColumnValue = super.incrementColumnValue(row, family, qualifier, amount);
                try {
                    FieldType fieldType = basicTypeManager.getFieldTypeByName(new QName("NS", "name1"));
                    fieldType.setName(new QName("NS", "name2"));
                    basicTypeManager.updateFieldType(fieldType);
                } catch (Exception e) {
                    e.printStackTrace();
                    fail();
                } 
                return incrementColumnValue;
            }
        };
        
        TypeManager typeManager = new HBaseTypeManager(new IdGeneratorImpl(), HBASE_PROXY.getConf(), zooKeeper, hbaseTableFactory) {
            @Override
            protected HTableInterface getTypeTable() {
                return typeTable;
            }
        };
        basicTypeManager.createFieldType(typeManager.newFieldType(valueType, new QName("NS", "name1"), Scope.VERSIONED));
        try {
            typeManager.createFieldType(typeManager.newFieldType(valueType, new QName("NS", "name2"), Scope.VERSIONED));
            fail();
        } catch (TypeException expected) {
        } catch (FieldTypeExistsException expected) {
            // This will be thrown when the cache of the typeManager was updated as a consequence of the update on basicTypeManager
            // Through ZooKeeper the cache will have been marked as invalidated
        }
        typeManager.close();
    }
    
    @Test
    public void testGetTypeIgnoresConcurrentCounterRows() throws Exception {
        HTableInterface typeTable = new LocalHTable(HBASE_PROXY.getConf(), Bytes.toBytes("type"));
        TypeManager typeManager = new HBaseTypeManager(new IdGeneratorImpl(), HBASE_PROXY.getConf(), zooKeeper, hbaseTableFactory);
        UUID id = UUID.randomUUID();
        byte[] rowId;
        rowId = new byte[16];
        Bytes.putLong(rowId, 0, id.getMostSignificantBits());
        Bytes.putLong(rowId, 8, id.getLeastSignificantBits());
        typeTable.incrementColumnValue(rowId, DATA_COLUMN_FAMILY, CONCURRENT_COUNTER_COLUMN_NAME, 1);
        try {
            typeManager.getFieldTypeById(id.toString());
            fail();
        } catch (FieldTypeNotFoundException expected) {
        }
        try {
            typeManager.getRecordTypeById(id.toString(), null);
            fail();
        } catch (RecordTypeNotFoundException expected) {
        }
        typeManager.close();
    }
}
