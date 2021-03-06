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
package org.lealone.storage.replication;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import org.lealone.db.CommandParameter;
import org.lealone.db.async.AsyncCallback;
import org.lealone.db.async.AsyncHandler;
import org.lealone.db.async.AsyncResult;
import org.lealone.db.async.Future;
import org.lealone.db.result.Result;
import org.lealone.server.protocol.replication.ReplicationUpdateAck;
import org.lealone.sql.SQLCommand;
import org.lealone.storage.replication.WriteResponseHandler.ReplicationResultHandler;

class ReplicationSQLCommand extends ReplicationCommand<ReplicaSQLCommand> implements SQLCommand {

    ReplicationSQLCommand(ReplicationSession session, ReplicaSQLCommand[] commands) {
        super(session, commands);
    }

    @Override
    public int getType() {
        return REPLICATION_SQL_COMMAND;
    }

    @Override
    public int getFetchSize() {
        return commands[0].getFetchSize();
    }

    @Override
    public void setFetchSize(int fetchSize) {
        for (ReplicaSQLCommand c : commands)
            c.setFetchSize(fetchSize);
    }

    @Override
    public List<? extends CommandParameter> getParameters() {
        int size = commands[0].getParameters().size();
        ArrayList<ReplicationCommandParameter> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            ArrayList<CommandParameter> parameters = new ArrayList<>(commands.length);
            for (int j = 0; j < commands.length; j++) {
                parameters.add(commands[j].getParameters().get(i));
            }
            ReplicationCommandParameter p = new ReplicationCommandParameter(i, parameters);
            list.add(p);
        }
        return list;
    }

    @Override
    public Result getMetaData() {
        return commands[0].getMetaData();
    }

    @Override
    public boolean isQuery() {
        return commands[0].isQuery();
    }

    @Override
    public Future<Result> executeQuery(int maxRows, boolean scrollable) {
        AsyncCallback<Result> ac = new AsyncCallback<>();
        HashSet<ReplicaSQLCommand> seen = new HashSet<>();
        executeQuery(maxRows, scrollable, 1, seen, ac);
        return ac;
    }

    private void executeQuery(int maxRows, boolean scrollable, int tries, HashSet<ReplicaSQLCommand> seen,
            AsyncCallback<Result> ac) {
        AsyncHandler<AsyncResult<Result>> handler = ar -> {
            if (ar.isFailed() && tries < session.maxTries && tries < session.r) {
                executeQuery(maxRows, scrollable, tries + 1, seen, ac);
            } else {
                ac.setAsyncResult(ar);
            }
        };
        ReadResponseHandler<Result> readResponseHandler = new ReadResponseHandler<>(session, handler);

        // 随机选择1个节点，如果读不到再试其他节点
        ReplicaSQLCommand c = getRandomNode(seen);
        if (c != null)
            c.executeQuery(maxRows, scrollable).onComplete(readResponseHandler);
    }

    @Override
    public Future<Integer> executeUpdate() {
        AsyncCallback<Integer> ac = new AsyncCallback<>();
        executeUpdate(1, ac);
        return ac;
    }

    private void executeUpdate(int tries, AsyncCallback<Integer> ac) {
        final String rn = session.createReplicationName();
        ReplicationResultHandler<ReplicationUpdateAck> replicationResultHandler = results -> {
            return handleReplicationConflict(rn, results);
        };
        AsyncHandler<AsyncResult<ReplicationUpdateAck>> finalResultHandler = ar -> {
            if (ar.isFailed() && tries < session.maxTries) {
                executeUpdate(tries + 1, ac);
            } else {
                // 如果为null，说明还没有确定该返回什么结果，那就让客户端继续等待
                if (ar.getResult() != null)
                    ac.setAsyncResult(new AsyncResult<>(ar.getResult().updateCount));
            }
        };
        // commands参数设为null，在handleReplicationConflict中处理提交
        WriteResponseHandler<ReplicationUpdateAck> writeResponseHandler = new WriteResponseHandler<>(session, null,
                finalResultHandler, replicationResultHandler);

        for (int i = 0; i < session.n; i++) {
            commands[i].executeReplicaUpdate(rn).onComplete(writeResponseHandler);
        }
    }

    private List<ReplicationUpdateAck> getLastAckResults(List<ReplicationUpdateAck> ackResults) {
        int maxAckVersion = 0;
        for (ReplicationUpdateAck ack : ackResults) {
            if (ack.ackVersion > maxAckVersion)
                maxAckVersion = ack.ackVersion;
        }
        List<ReplicationUpdateAck> newAckResults = new ArrayList<>(session.n);
        for (ReplicationUpdateAck ack : ackResults) {
            if (ack.ackVersion == maxAckVersion)
                newAckResults.add(ack);
        }
        if (newAckResults.size() < session.n)
            return null;
        else
            return newAckResults;
    }

    private ReplicationUpdateAck handleReplicationConflict(String replicationName,
            List<ReplicationUpdateAck> ackResults) {
        // 如果存在isFinalResult为true的响应结果就可以结束等待了
        ReplicationUpdateAck ret = null;
        for (ReplicationUpdateAck ack : ackResults) {
            if (ack.isFinalResult) {
                ret = ack;
                ack.getReplicaCommand().removeAsyncCallback(ack.getPacketId());
            }
        }
        if (ret != null)
            return ret;

        ackResults = getLastAckResults(ackResults);
        if (ackResults == null)
            return null;
        ReplicationConflictType replicationConflictType = ReplicationConflictType.NONE;
        for (ReplicationUpdateAck ack : ackResults) {
            if (ack.replicationConflictType != ReplicationConflictType.NONE) {
                replicationConflictType = ack.replicationConflictType;
                break;
            }
        }

        switch (replicationConflictType) {
        case ROW_LOCK:
        case DB_OBJECT_LOCK:
            return handleLockConflict(replicationName, ackResults);
        case APPEND:
            return handleAppendConflict(replicationName, ackResults);
        case NONE:
        default:
            for (ReplicationUpdateAck ack : ackResults) {
                ack.getReplicaCommand().removeAsyncCallback(ack.getPacketId());
                ack.getReplicaCommand().handleReplicaConflict(null);
            }
            return ackResults.get(0);
        }
    }

    private ReplicationUpdateAck handleAppendConflict(String replicationName, List<ReplicationUpdateAck> ackResults) {
        long minKey = Long.MAX_VALUE;
        int quorum = session.n / 2 + 1;
        String validReplicationName = null;
        HashMap<String, AtomicInteger> groupResults = new HashMap<>(1);
        for (ReplicationUpdateAck ack : ackResults) {
            if (ack.uncommittedReplicationName == null)
                ack.uncommittedReplicationName = replicationName;
            String name = ack.uncommittedReplicationName;
            AtomicInteger counter = groupResults.get(name);
            if (counter == null) {
                counter = new AtomicInteger();
                groupResults.put(name, counter);
            }
            if (counter.incrementAndGet() >= quorum) {
                validReplicationName = name;
            }
            if (ack.first >= 0 && ack.first < minKey) {
                minKey = ack.first;
            }
        }

        TreeSet<String> retryReplicationNames = new TreeSet<>(groupResults.keySet());
        if (validReplicationName == null) {
            validReplicationName = retryReplicationNames.pollFirst();
        } else {
            retryReplicationNames.remove(validReplicationName);
        }

        if (validReplicationName.equals(replicationName) || retryReplicationNames.contains(replicationName)) {
            ArrayList<String> appendReplicationNames = new ArrayList<>(groupResults.size() + 1);
            ArrayList<String> replicationNames = new ArrayList<>(groupResults.size() + 1);
            appendReplicationNames.add(validReplicationName);
            for (String n : retryReplicationNames) {
                appendReplicationNames.add(n);
            }
            for (int i = 0; i < appendReplicationNames.size(); i++) {
                String name = appendReplicationNames.get(i);
                for (ReplicationUpdateAck ack : ackResults) {
                    if (ack.uncommittedReplicationName.equals(name)) {
                        long first = minKey;
                        minKey = first + ack.updateCount;
                        replicationNames.add(first + "," + ack.updateCount + ":" + name);
                        break;
                    }
                }
            }

            for (ReplicationUpdateAck ack : ackResults) {
                ack.getReplicaCommand().removeAsyncCallback(ack.getPacketId());
            }
            if (validReplicationName.equals(replicationName)) {
                for (ReplicationUpdateAck ack : ackResults) {
                    ack.getReplicaCommand().handleReplicaConflict(replicationNames);
                }
                for (ReplicationUpdateAck ack : ackResults) {
                    if (ack.uncommittedReplicationName.equals(validReplicationName)) {
                        return ack;
                    }
                }
            } else {
                for (ReplicationUpdateAck ack : ackResults) {
                    if (ack.uncommittedReplicationName.equals(replicationName)) {
                        for (int i = 0, size = replicationNames.size(); i < size; i++) {
                            String name = appendReplicationNames.get(i);
                            if (ack.uncommittedReplicationName.equals(name)) {
                                name = replicationNames.get(i);
                                String[] keys = name.substring(0, name.indexOf(':')).split(",");
                                long first = Long.parseLong(keys[0]);
                                return new ReplicationUpdateAck(ack.updateCount, first, ack.uncommittedReplicationName,
                                        ack.replicationConflictType, ack.ackVersion, ack.isIfDDL, ack.isFinalResult);
                            }
                        }
                    }
                }
            }
        }
        return null; // 继续等待
    }

    private ReplicationUpdateAck handleLockConflict(String replicationName, List<ReplicationUpdateAck> ackResults) {
        int quorum = session.n / 2 + 1;
        String validReplicationName = null;
        HashMap<String, AtomicInteger> groupResults = new HashMap<>(1);
        for (ReplicationUpdateAck ack : ackResults) {
            if (ack.uncommittedReplicationName == null)
                ack.uncommittedReplicationName = replicationName;
            String name = ack.uncommittedReplicationName;
            AtomicInteger counter = groupResults.get(name);
            if (counter == null) {
                counter = new AtomicInteger();
                groupResults.put(name, counter);
            }
            if (counter.incrementAndGet() >= quorum) {
                validReplicationName = name;
            }
        }

        TreeSet<String> retryReplicationNames = new TreeSet<>(groupResults.keySet());
        if (validReplicationName == null) {
            validReplicationName = retryReplicationNames.pollFirst();
        } else {
            retryReplicationNames.remove(validReplicationName);
        }

        if (validReplicationName.equals(replicationName)) {
            ArrayList<String> replicationNames = new ArrayList<>(retryReplicationNames.size());
            replicationNames.addAll(retryReplicationNames);
            ReplicationUpdateAck ret = null;
            for (ReplicationUpdateAck ack : ackResults) {
                ack.getReplicaCommand().removeAsyncCallback(ack.getPacketId());
                ack.getReplicaCommand().handleReplicaConflict(replicationNames);
                if (ret == null && ack.uncommittedReplicationName.equals(validReplicationName))
                    ret = ack;
            }
            return ret;
        } else if (retryReplicationNames.contains(replicationName) && ackResults.get(0).isIfDDL) {
            // 带IF的DDL语句不需要等了，返回的结果都是一样的
            ReplicationUpdateAck ret = null;
            for (ReplicationUpdateAck ack : ackResults) {
                ack.getReplicaCommand().removeAsyncCallback(ack.getPacketId());
                if (ret == null && ack.uncommittedReplicationName.equals(validReplicationName))
                    ret = ack;
            }
            return ret;
        }
        return null; // 继续等待
    }
}
