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
package org.lealone.server.protocol.statement;

import java.io.IOException;

import org.lealone.db.result.Result;
import org.lealone.net.NetInputStream;
import org.lealone.net.NetOutputStream;
import org.lealone.server.protocol.AckPacket;
import org.lealone.server.protocol.PacketDecoder;
import org.lealone.server.protocol.PacketType;
import org.lealone.server.protocol.ps.PreparedStatementGetMetaDataAck;
import org.lealone.server.protocol.result.ResultFetchRowsAck;

public class StatementQueryAck implements AckPacket {

    public final Result result;
    public final int rowCount;
    public final int columnCount;
    public final int fetchSize;
    public final NetInputStream in;

    public StatementQueryAck(Result result, int rowCount, int fetchSize) {
        this.result = result;
        this.rowCount = rowCount;
        this.fetchSize = fetchSize;
        columnCount = result.getVisibleColumnCount();
        in = null;
    }

    public StatementQueryAck(NetInputStream in, int version) throws IOException {
        result = null;
        rowCount = in.readInt();
        columnCount = in.readInt();
        fetchSize = in.readInt();
        this.in = in;
    }

    @Override
    public PacketType getType() {
        return PacketType.STATEMENT_QUERY_ACK;
    }

    @Override
    public void encode(NetOutputStream out, int version) throws IOException {
        out.writeInt(rowCount);
        out.writeInt(columnCount);
        out.writeInt(fetchSize);
        encodeExt(out, version);
        for (int i = 0; i < columnCount; i++) {
            PreparedStatementGetMetaDataAck.writeColumn(out, result, i);
        }
        ResultFetchRowsAck.writeRow(out, result, fetchSize);
    }

    // ----------------------------------------------------------------
    // 因为查询结果集是lazy解码的，所以子类的字段需要提前编码和解码
    // ----------------------------------------------------------------
    public void encodeExt(NetOutputStream out, int version) throws IOException {
    }

    public static final Decoder decoder = new Decoder();

    private static class Decoder implements PacketDecoder<StatementQueryAck> {
        @Override
        public StatementQueryAck decode(NetInputStream in, int version) throws IOException {
            return new StatementQueryAck(in, version);
        }
    }
}
