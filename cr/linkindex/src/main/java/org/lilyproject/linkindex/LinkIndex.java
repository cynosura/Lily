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
package org.lilyproject.linkindex;

import org.apache.hadoop.hbase.util.Bytes;
import org.lilyproject.hbaseindex.*;
import org.lilyproject.linkindex.LinkIndexMetrics.Action;
import org.lilyproject.repository.api.IdGenerator;
import org.lilyproject.repository.api.RecordId;
import org.lilyproject.repository.api.Repository;
import org.lilyproject.util.Pair;
import org.lilyproject.util.io.Closer;

import java.io.IOException;
import java.util.*;

/**
 * The index of links that exist between documents.
 */
// IMPORTANT implementation note: the order in which changes are applied, first to the forward or first to
// the backward table, is not arbitrary. It is such that if the process would fail in between, there would
// never be left any state in the backward table which would not be found via the forward index.
public class LinkIndex {
    private IdGenerator idGenerator;
    private LinkIndexMetrics metrics;
    private static ThreadLocal<Index> FORWARD_INDEX;
    private static ThreadLocal<Index> BACKWARD_INDEX;

    private static final byte[] SOURCE_FIELD_KEY = Bytes.toBytes("sf");
    private static final byte[] VTAG_KEY = Bytes.toBytes("vt");

    public LinkIndex(final IndexManager indexManager, Repository repository) throws IndexNotFoundException, IOException {
        metrics = new LinkIndexMetrics("linkIndex");
        this.idGenerator = repository.getIdGenerator();

        FORWARD_INDEX = new ThreadLocal<Index>() {
            @Override
            protected Index initialValue() {
                try {
                    return indexManager.getIndex("links-forward");
                } catch (Exception e) {
                    throw new RuntimeException("Error accessing forward links index.", e);
                }
            }
        };

        BACKWARD_INDEX = new ThreadLocal<Index>() {
            @Override
            protected Index initialValue() {
                try {
                    return indexManager.getIndex("links-backward");
                } catch (Exception e) {
                    throw new RuntimeException("Error accessing backward links index.", e);
                }
            }
        };
    }

    /**
     * Deletes all links of a record, irrespective of the vtag.
     */
    public void deleteLinks(RecordId sourceRecord) throws IOException {
        long before = System.currentTimeMillis();
        try {
            byte[] sourceAsBytes = sourceRecord.toBytes();
    
            // Read links from the forwards table
            Set<Pair<FieldedLink, String>> oldLinks = getAllForwardLinks(sourceRecord);
    
            // Delete existing entries from the backwards table
            List<IndexEntry> entries = new ArrayList<IndexEntry>(oldLinks.size());
            for (Pair<FieldedLink, String> link : oldLinks) {
                IndexEntry entry = createBackwardIndexEntry(link.getV2(), link.getV1().getRecordId(), link.getV1().getFieldTypeId());
                entry.setIdentifier(sourceAsBytes);
                entries.add(entry);
            }
            BACKWARD_INDEX.get().removeEntries(entries);
    
            // Delete existing entries from the forwards table
            entries.clear();
            for (Pair<FieldedLink, String> link : oldLinks) {
                IndexEntry entry = createForwardIndexEntry(link.getV2(), sourceRecord, link.getV1().getFieldTypeId());
                entry.setIdentifier(link.getV1().getRecordId().toBytes());
                entries.add(entry);
            }
            FORWARD_INDEX.get().removeEntries(entries);
        } finally {
            metrics.report(Action.DELETE_LINKS, System.currentTimeMillis() - before);
        }
    }

    public void deleteLinks(RecordId sourceRecord, String vtag) throws IOException {
        long before = System.currentTimeMillis();
        try {
            byte[] sourceAsBytes = sourceRecord.toBytes();
    
            // Read links from the forwards table
            Set<FieldedLink> oldLinks = getForwardLinks(sourceRecord, vtag);
    
            // Delete existing entries from the backwards table
            List<IndexEntry> entries = new ArrayList<IndexEntry>(oldLinks.size());
            for (FieldedLink link : oldLinks) {
                IndexEntry entry = createBackwardIndexEntry(vtag, link.getRecordId(), link.getFieldTypeId());
                entry.setIdentifier(sourceAsBytes);
                entries.add(entry);
            }
            BACKWARD_INDEX.get().removeEntries(entries);
    
            // Delete existing entries from the forwards table
            entries.clear();
            for (FieldedLink link : oldLinks) {
                IndexEntry entry = createForwardIndexEntry(vtag, sourceRecord, link.getFieldTypeId());
                entry.setIdentifier(link.getRecordId().toBytes());
                entries.add(entry);
            }
            FORWARD_INDEX.get().removeEntries(entries);
        } finally {
            metrics.report(Action.DELETE_LINKS_VTAG, System.currentTimeMillis() - before);
        }
    }

    /**
     *
     * @param links if this set is empty, then calling this method is equivalent to calling deleteLinks
     */
    public void updateLinks(RecordId sourceRecord, String vtag, Set<FieldedLink> links) throws IOException {
        long before = System.currentTimeMillis();
        try {
            // We could simply delete all the old entries using deleteLinks() and then add
            // all new entries, but instead we find out what actually needs adding or removing and only
            // perform that. This is to avoid running into problems due to http://search-hadoop.com/m/rNnhN15Xecu
            // (= delete and put within the same millisecond).
            byte[] sourceAsBytes = sourceRecord.toBytes();
    
            Set<FieldedLink> oldLinks = getForwardLinks(sourceRecord, vtag);
    
            // Find out what changed
            Set<FieldedLink> removedLinks = new HashSet<FieldedLink>(oldLinks);
            removedLinks.removeAll(links);
            Set<FieldedLink> addedLinks = new HashSet<FieldedLink>(links);
            addedLinks.removeAll(oldLinks);
    
            // Apply added links
            List<IndexEntry> fwdEntries = null;
            List<IndexEntry> bkwdEntries = null;
            if (addedLinks.size() > 0) {
                fwdEntries = new ArrayList<IndexEntry>(Math.max(addedLinks.size(), removedLinks.size()));
                bkwdEntries = new ArrayList<IndexEntry>(fwdEntries.size());
                for (FieldedLink link : addedLinks) {
                    IndexEntry fwdEntry = createForwardIndexEntry(vtag, sourceRecord, link.getFieldTypeId());
                    fwdEntry.setIdentifier(link.getRecordId().toBytes());
                    fwdEntries.add(fwdEntry);
    
                    IndexEntry bkwdEntry = createBackwardIndexEntry(vtag, link.getRecordId(), link.getFieldTypeId());
                    bkwdEntry.setIdentifier(sourceAsBytes);
                    bkwdEntries.add(bkwdEntry);
                }
                FORWARD_INDEX.get().addEntries(fwdEntries);
                BACKWARD_INDEX.get().addEntries(bkwdEntries);
            }
    
            // Apply removed links
            if (removedLinks.size() > 0) {
                if (fwdEntries != null) {
                    fwdEntries.clear();
                    bkwdEntries.clear();
                } else {
                    fwdEntries = new ArrayList<IndexEntry>(removedLinks.size());
                    bkwdEntries = new ArrayList<IndexEntry>(fwdEntries.size());
                }
    
                for (FieldedLink link : removedLinks) {
                    IndexEntry bkwdEntry = createBackwardIndexEntry(vtag, link.getRecordId(), link.getFieldTypeId());
                    bkwdEntry.setIdentifier(sourceAsBytes);
                    bkwdEntries.add(bkwdEntry);
    
                    IndexEntry fwdEntry = createForwardIndexEntry(vtag, sourceRecord, link.getFieldTypeId());
                    fwdEntry.setIdentifier(link.getRecordId().toBytes());
                    fwdEntries.add(fwdEntry);
                }
                BACKWARD_INDEX.get().removeEntries(bkwdEntries);
                FORWARD_INDEX.get().removeEntries(fwdEntries);
            }
        } finally {
            metrics.report(Action.UPDATE_LINKS, System.currentTimeMillis() - before);
        }
    }

    private IndexEntry createBackwardIndexEntry(String vtag, RecordId target, String sourceField) {
        IndexEntry entry = new IndexEntry();

        byte[] sourceFieldBytes = idToBytes(sourceField);

        entry.addField("vtag", vtag);
        entry.addField("target", target.toBytes());
        entry.addField("sourcefield", sourceFieldBytes);

        entry.addData(SOURCE_FIELD_KEY, sourceFieldBytes);

        return entry;
    }

    private IndexEntry createForwardIndexEntry(String vtag, RecordId source, String sourceField) {
        IndexEntry entry = new IndexEntry();

        byte[] sourceFieldBytes = idToBytes(sourceField);

        entry.addField("vtag", vtag);
        entry.addField("source", source.toBytes());
        entry.addField("sourcefield", sourceFieldBytes);

        entry.addData(SOURCE_FIELD_KEY, sourceFieldBytes);
        entry.addData(VTAG_KEY, Bytes.toBytes(vtag));

        return entry;
    }

    public Set<RecordId> getReferrers(RecordId record, String vtag) throws IOException {
        return getReferrers(record, vtag, null);
    }

    public Set<RecordId> getReferrers(RecordId record, String vtag, String sourceField) throws IOException {
        long before = System.currentTimeMillis();
        try {
            Query query = new Query();
            query.addEqualsCondition("vtag", vtag);
            query.addEqualsCondition("target", record.toBytes());
            if (sourceField != null) {
                query.addEqualsCondition("sourcefield", idToBytes(sourceField));
            }
    
            Set<RecordId> result = new HashSet<RecordId>();
    
            QueryResult qr = BACKWARD_INDEX.get().performQuery(query);
            byte[] id;
            while ((id = qr.next()) != null) {
                result.add(idGenerator.fromBytes(id));
            }
            Closer.close(qr); // Not closed in finally block: avoid HBase contact when there could be connection problems.
    
            return result;
        } finally {
            metrics.report(Action.GET_REFERRERS, System.currentTimeMillis() - before);
        }
    }

    public Set<FieldedLink> getFieldedReferrers(RecordId record, String vtag) throws IOException {
        long before = System.currentTimeMillis();
        try {
            Query query = new Query();
            query.addEqualsCondition("target", record.toBytes());
            query.addEqualsCondition("vtag", vtag);
    
            Set<FieldedLink> result = new HashSet<FieldedLink>();
    
            QueryResult qr = BACKWARD_INDEX.get().performQuery(query);
            byte[] id;
            while ((id = qr.next()) != null) {
                String sourceField = idFromBytes(qr.getData(SOURCE_FIELD_KEY));
                result.add(new FieldedLink(idGenerator.fromBytes(id), sourceField));
            }
            Closer.close(qr); // Not closed in finally block: avoid HBase contact when there could be connection problems.
    
            return result;
        } finally {
            metrics.report(Action.GET_FIELDED_REFERRERS, System.currentTimeMillis() - before);
        }
    }

    public Set<Pair<FieldedLink, String>> getAllForwardLinks(RecordId record) throws IOException {
        long before = System.currentTimeMillis();
        try {
            Query query = new Query();
            query.addEqualsCondition("source", record.toBytes());

            Set<Pair<FieldedLink, String>> result = new HashSet<Pair<FieldedLink, String>>();
    
            QueryResult qr = FORWARD_INDEX.get().performQuery(query);
            byte[] id;
            while ((id = qr.next()) != null) {
                String sourceField = idFromBytes(qr.getData(SOURCE_FIELD_KEY));
                String vtag = Bytes.toString(qr.getData(VTAG_KEY));
                result.add(new Pair<FieldedLink, String>(new FieldedLink(idGenerator.fromBytes(id), sourceField), vtag));
            }
            Closer.close(qr); // Not closed in finally block: avoid HBase contact when there could be connection problems.
    
            return result;
        } finally {
            metrics.report(Action.GET_ALL_FW_LINKS, System.currentTimeMillis() - before);
        }
    }

    public Set<FieldedLink> getForwardLinks(RecordId record, String vtag) throws IOException {
        long before = System.currentTimeMillis();
        try {
            Query query = new Query();
            query.addEqualsCondition("source", record.toBytes());
            query.addEqualsCondition("vtag", vtag);
    
            Set<FieldedLink> result = new HashSet<FieldedLink>();
    
            QueryResult qr = FORWARD_INDEX.get().performQuery(query);
            byte[] id;
            while ((id = qr.next()) != null) {
                String sourceField = idFromBytes(qr.getData(SOURCE_FIELD_KEY));
                result.add(new FieldedLink(idGenerator.fromBytes(id), sourceField));
            }
            Closer.close(qr); // Not closed in finally block: avoid HBase contact when there could be connection problems.
    
            return result;
        } finally {
            metrics.report(Action.GET_FW_LINKS, System.currentTimeMillis() - before);
        }
    }

    private String formatVariantProps(SortedMap<String, String> props) {
        if (props.isEmpty())
            return null;

        // This string-formatting logic is similar to what is in VariantRecordId, which at the time of
        // this writing was decided to keep private.
        boolean first = true;
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> prop : props.entrySet()) {
            if (!first) {
                builder.append(":");
            }
            builder.append(prop.getKey());
            builder.append(",");
            builder.append(prop.getValue());
            first = false;
        }

        return builder.toString();
    }

    public static void createIndexes(IndexManager indexManager) throws IOException {

        // About the structure of these indexes:
        //  - the vtag comes after the recordid because this way we can delete all
        //    entries for a record without having to know the vtags under which they occur
        //  - the sourcefield will often by optional in queries, that's why it comes last

        {
            IndexDefinition indexDef = new IndexDefinition("links-backward");
            indexDef.addByteField("target");
            indexDef.addStringField("vtag");
            indexDef.addByteField("sourcefield");
            indexManager.createIndexIfNotExists(indexDef);
        }

        {
            IndexDefinition indexDef = new IndexDefinition("links-forward");
            indexDef.addByteField("source");
            indexDef.addStringField("vtag");
            indexDef.addByteField("sourcefield");
            indexManager.createIndexIfNotExists(indexDef);
        }
    }

    private byte[] idToBytes(String id) {
        UUID uuid = UUID.fromString(id);
        byte[] rowId;
        rowId = new byte[16];
        Bytes.putLong(rowId, 0, uuid.getMostSignificantBits());
        Bytes.putLong(rowId, 8, uuid.getLeastSignificantBits());
        return rowId;
    }

    private String idFromBytes(byte[] bytes) {
        UUID uuid = new UUID(Bytes.toLong(bytes, 0, 8), Bytes.toLong(bytes, 8, 8));
        return uuid.toString();
    }
}
