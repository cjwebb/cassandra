/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.io.sstable;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import com.google.common.util.concurrent.Uninterruptibles;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;

import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.config.Config;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.Keyspace;
import org.apache.cassandra.db.SerializationHeader;
import org.apache.cassandra.db.lifecycle.LifecycleTransaction;
import org.apache.cassandra.db.rows.EncodingStats;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.io.sstable.format.SSTableWriter;
import org.apache.cassandra.schema.KeyspaceParams;
import org.apache.cassandra.utils.FBUtilities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SSTableWriterTestBase extends SchemaLoader
{

    protected static final String KEYSPACE = "SSTableRewriterTest";
    protected static final String CF = "Standard1";

    private static Config.DiskAccessMode standardMode;
    private static Config.DiskAccessMode indexMode;

    @BeforeClass
    public static void defineSchema() throws ConfigurationException
    {
        if (FBUtilities.isWindows())
        {
            standardMode = DatabaseDescriptor.getDiskAccessMode();
            indexMode = DatabaseDescriptor.getIndexAccessMode();

            DatabaseDescriptor.setDiskAccessMode(Config.DiskAccessMode.standard);
            DatabaseDescriptor.setIndexAccessMode(Config.DiskAccessMode.standard);
        }

        SchemaLoader.prepareServer();
        SchemaLoader.createKeyspace(KEYSPACE,
                                    KeyspaceParams.simple(1),
                                    SchemaLoader.standardCFMD(KEYSPACE, CF));
    }

    @AfterClass
    public static void revertDiskAccess()
    {
        DatabaseDescriptor.setDiskAccessMode(standardMode);
        DatabaseDescriptor.setIndexAccessMode(indexMode);
    }

    @After
    public void truncateCF()
    {
        Keyspace keyspace = Keyspace.open(KEYSPACE);
        ColumnFamilyStore store = keyspace.getColumnFamilyStore(CF);
        store.truncateBlocking();
        LifecycleTransaction.waitForDeletions();
    }

    public static void truncate(ColumnFamilyStore cfs)
    {
        cfs.truncateBlocking();
        LifecycleTransaction.waitForDeletions();
        Uninterruptibles.sleepUninterruptibly(10L, TimeUnit.MILLISECONDS);
        assertEquals(0, cfs.metric.liveDiskSpaceUsed.getCount());
        assertEquals(0, cfs.metric.totalDiskSpaceUsed.getCount());
        validateCFS(cfs);
    }

    public static void validateCFS(ColumnFamilyStore cfs)
    {
        Set<Integer> liveDescriptors = new HashSet<>();
        long spaceUsed = 0;
        for (SSTableReader sstable : cfs.getLiveSSTables())
        {
            assertFalse(sstable.isMarkedCompacted());
            assertEquals(1, sstable.selfRef().globalCount());
            liveDescriptors.add(sstable.descriptor.generation);
            spaceUsed += sstable.bytesOnDisk();
        }
        for (File dir : cfs.getDirectories().getCFDirectories())
        {
            for (File f : dir.listFiles())
            {
                if (f.getName().contains("Data"))
                {
                    Descriptor d = Descriptor.fromFilename(f.getAbsolutePath());
                    assertTrue(d.toString(), liveDescriptors.contains(d.generation));
                }
            }
        }
        assertEquals(spaceUsed, cfs.metric.liveDiskSpaceUsed.getCount());
        assertEquals(spaceUsed, cfs.metric.totalDiskSpaceUsed.getCount());
        assertTrue(cfs.getTracker().getCompacting().isEmpty());
    }

    public static SSTableWriter getWriter(ColumnFamilyStore cfs, File directory, LifecycleTransaction txn)
    {
        String filename = cfs.getSSTablePath(directory);
        return SSTableWriter.create(filename, 0, 0, new SerializationHeader(true, cfs.metadata, cfs.metadata.partitionColumns(), EncodingStats.NO_STATS), cfs.indexManager.listIndexes(), txn);
    }

    public static ByteBuffer random(int i, int size)
    {
        byte[] bytes = new byte[size + 4];
        ThreadLocalRandom.current().nextBytes(bytes);
        ByteBuffer r = ByteBuffer.wrap(bytes);
        r.putInt(0, i);
        return r;
    }

    public static int assertFileCounts(String [] files)
    {
        int tmplinkcount = 0;
        int tmpcount = 0;
        int datacount = 0;
        for (String f : files)
        {
            if (f.endsWith("-CRC.db"))
                continue;
            if (f.contains("tmplink-"))
                tmplinkcount++;
            else if (f.contains("tmp-"))
                tmpcount++;
            else if (f.contains("Data"))
                datacount++;
        }
        assertEquals(0, tmplinkcount);
        assertEquals(0, tmpcount);
        return datacount;
    }
}
