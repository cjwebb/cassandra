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
package org.apache.cassandra.index.sasi;

import java.nio.ByteBuffer;

import org.apache.cassandra.index.sasi.disk.OnDiskIndexBuilder.TermSize;
import org.apache.cassandra.index.sasi.utils.MappedBuffer;
import org.apache.cassandra.db.marshal.AbstractType;

public class Term
{
    protected final MappedBuffer content;
    protected final TermSize termSize;


    public Term(MappedBuffer content, TermSize size)
    {
        this.content = content;
        this.termSize = size;
    }

    public ByteBuffer getTerm()
    {
        long offset = termSize.isConstant() ? content.position() : content.position() + 2;
        int  length = termSize.isConstant() ? termSize.size : content.getShort(content.position());

        return content.getPageRegion(offset, length);
    }

    public long getDataOffset()
    {
        long position = content.position();
        return position + (termSize.isConstant() ? termSize.size : 2 + content.getShort(position));
    }

    public int compareTo(AbstractType<?> comparator, ByteBuffer query)
    {
        return compareTo(comparator, query, true);
    }

    public int compareTo(AbstractType<?> comparator, ByteBuffer query, boolean checkFully)
    {
        long position = content.position();
        int padding = termSize.isConstant() ? 0 : 2;
        int len = termSize.isConstant() ? termSize.size : content.getShort(position);

        return content.comparePageTo(position + padding, checkFully ? len : Math.min(len, query.remaining()), comparator, query);
    }
}
