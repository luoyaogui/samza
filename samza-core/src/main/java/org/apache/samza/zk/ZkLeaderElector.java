/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.samza.zk;

import com.google.common.annotations.VisibleForTesting;
import org.I0Itec.zkclient.IZkDataListener;
import org.apache.samza.SamzaException;
import org.apache.samza.coordinator.leaderelection.LeaderElector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <p>
 * An implementation of Leader Elector using Zookeeper.
 *
 * Each participant in the leader election process creates an instance of this class and tries to become the leader.
 * The participant with the lowest sequence number in the ZK subtree for election becomes the leader. Every non-leader
 * sets a watcher on its predecessor, where the predecessor is the participant with the largest sequence number
 * that is less than the current participant's sequence number.
 * </p>
 * */
public class ZkLeaderElector implements LeaderElector {
  public static final Logger LOGGER = LoggerFactory.getLogger(ZkLeaderElector.class);
  private final ZkUtils zkUtils;
  private final String processorIdStr;
  private final ZkKeyBuilder keyBuilder;
  private final String hostName;

  private AtomicBoolean isLeader = new AtomicBoolean(false);
  private final IZkDataListener zkLeaderElectionListener;
  private String currentSubscription = null;
  private final Random random = new Random();

  @VisibleForTesting
  ZkLeaderElector(String processorIdStr, ZkUtils zkUtils, IZkDataListener leaderElectionListener) {
    this.processorIdStr = processorIdStr;
    this.zkUtils = zkUtils;
    this.zkLeaderElectionListener = leaderElectionListener;
    this.keyBuilder = this.zkUtils.getKeyBuilder();
    this.hostName = getHostName();
  }

  public ZkLeaderElector(String processorIdStr, ZkUtils zkUtils) {
    this.zkLeaderElectionListener = new ZkLeaderElectionListener();
    this.processorIdStr = processorIdStr;
    this.zkUtils = zkUtils;
    this.keyBuilder = this.zkUtils.getKeyBuilder();
    this.hostName = getHostName();
  }

  // TODO: This should go away once we integrate with Zk based Job Coordinator
  private String getHostName() {
    try {
      return InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      LOGGER.error("Failed to fetch hostname of the processor", e);
      throw new SamzaException(e);
    }
  }

  @Override
  public boolean tryBecomeLeader() {
    String currentPath = zkUtils.registerProcessorAndGetId(hostName);

    List<String> children = zkUtils.getSortedActiveProcessors();
    LOGGER.debug(zLog("Current active processors - " + children));
    int index = children.indexOf(ZkKeyBuilder.parseIdFromPath(currentPath));

    if (children.size() == 0 || index == -1) {
      throw new SamzaException("Looks like we are no longer connected to Zk. Need to reconnect!");
    }

    if (index == 0) {
      isLeader.getAndSet(true);
      LOGGER.info(zLog("Eligible to become the leader!"));
      return true;
    }

    isLeader.getAndSet(false);
    LOGGER.info("Index = " + index + " Not eligible to be a leader yet!");
    String predecessor = children.get(index - 1);
    if (!predecessor.equals(currentSubscription)) {
      if (currentSubscription != null) {
        LOGGER.debug(zLog("Unsubscribing data change for " + currentSubscription));
        zkUtils.unsubscribeDataChanges(keyBuilder.getProcessorsPath() + "/" + currentSubscription, zkLeaderElectionListener);
      }
      currentSubscription = predecessor;
      LOGGER.info(zLog("Subscribing data change for " + predecessor));
      zkUtils.subscribeDataChanges(keyBuilder.getProcessorsPath() + "/" + currentSubscription, zkLeaderElectionListener);
    }
    /**
     * Verify that the predecessor still exists. This step is needed because the ZkClient subscribes for data changes
     * on the path, even if the path doesn't exist. Since we are using Ephemeral Sequential nodes, if the path doesn't
     * exist during subscription, it is not going to get created in the future.
     */
    boolean predecessorExists = zkUtils.exists(keyBuilder.getProcessorsPath() + "/" + currentSubscription);
    if (predecessorExists) {
      LOGGER.info(zLog("Predecessor still exists. Current subscription is valid. Continuing as non-leader."));
    } else {
      try {
        Thread.sleep(random.nextInt(1000));
      } catch (InterruptedException e) {
        Thread.interrupted();
      }
      LOGGER.info(zLog("Predecessor doesn't exist anymore. Trying to become leader again..."));
      return tryBecomeLeader();
    }
    return false;
  }

  @Override
  public void resignLeadership() {
    isLeader.compareAndSet(true, false);
  }

  @Override
  public boolean amILeader() {
    return isLeader.get();
  }

  private String zLog(String logMessage) {
    return String.format("[Processor-%s] %s", processorIdStr, logMessage);
  }

  // Only by non-leaders
  class ZkLeaderElectionListener implements IZkDataListener {

    @Override
    public void handleDataChange(String dataPath, Object data) throws Exception {
      LOGGER.debug("Data change on path: " + dataPath + " Data: " + data);
    }

    @Override
    public void handleDataDeleted(String dataPath) throws Exception {
      LOGGER.info(zLog("Data deleted on path " + dataPath + ". Predecessor went away. So, trying to become leader again..."));
      tryBecomeLeader();
    }
  }
}
