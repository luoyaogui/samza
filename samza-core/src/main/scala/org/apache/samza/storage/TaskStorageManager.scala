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

package org.apache.samza.storage

import java.io._
import java.util

import org.apache.samza.config.StorageConfig
import org.apache.samza.{Partition, SamzaException}
import org.apache.samza.container.TaskName
import org.apache.samza.system.StreamMetadataCache
import org.apache.samza.system.SystemAdmin
import org.apache.samza.system.SystemConsumer
import org.apache.samza.system.SystemStream
import org.apache.samza.system.SystemStreamPartition
import org.apache.samza.system.SystemStreamPartitionIterator
import org.apache.samza.system.ExtendedSystemAdmin
import org.apache.samza.system.SystemStreamMetadata
import org.apache.samza.util.Logging
import org.apache.samza.util.Util
import org.apache.samza.util.Clock

import scala.collection.{JavaConversions, Map}

object TaskStorageManager {
  def getStoreDir(storeBaseDir: File, storeName: String) = {
    new File(storeBaseDir, storeName)
  }

  def getStorePartitionDir(storeBaseDir: File, storeName: String, taskName: TaskName) = {
    // TODO: Sanitize, check and clean taskName string as a valid value for a file
    new File(storeBaseDir, (storeName + File.separator + taskName.toString).replace(' ', '_'))
  }
}

/**
 * Manage all the storage engines for a given task
 */
class TaskStorageManager(
  taskName: TaskName,
  taskStores: Map[String, StorageEngine] = Map(),
  storeConsumers: Map[String, SystemConsumer] = Map(),
  changeLogSystemStreams: Map[String, SystemStream] = Map(),
  changeLogStreamPartitions: Int,
  streamMetadataCache: StreamMetadataCache,
  storeBaseDir: File = new File(System.getProperty("user.dir"), "state"),
  loggedStoreBaseDir: File = new File(System.getProperty("user.dir"), "state"),
  partition: Partition,
  systemAdmins: Map[String, SystemAdmin],
  changeLogDeleteRetentionsInMs: Map[String, Long],
  clock: Clock) extends Logging {

  var taskStoresToRestore = taskStores.filter{
    case (storeName, storageEngine) => storageEngine.getStoreProperties.isLoggedStore
  }
  val persistedStores = taskStores.filter{
    case (storeName, storageEngine) => storageEngine.getStoreProperties.isPersistedToDisk
  }

  var changeLogOldestOffsets: Map[SystemStream, String] = Map()
  val fileOffset: util.Map[SystemStreamPartition, String] = new util.HashMap[SystemStreamPartition, String]()
  val offsetFileName = "OFFSET"

  def apply(storageEngineName: String) = taskStores(storageEngineName)

  def init {
    cleanBaseDirs()
    setupBaseDirs()
    validateChangelogStreams()
    startConsumers()
    restoreStores()
    stopConsumers()
  }

  private def cleanBaseDirs() {
    debug("Cleaning base directories for stores.")

    taskStores.keys.foreach(storeName => {
      val storagePartitionDir = TaskStorageManager.getStorePartitionDir(storeBaseDir, storeName, taskName)
      info("Got default storage partition directory as %s" format storagePartitionDir.toPath.toString)

      if(storagePartitionDir.exists()) {
        info("Deleting default storage partition directory %s" format storagePartitionDir.toPath.toString)
        Util.rm(storagePartitionDir)
      }

      val loggedStoreDir = TaskStorageManager.getStorePartitionDir(loggedStoreBaseDir, storeName, taskName)
      info("Got logged storage partition directory as %s" format loggedStoreDir.toPath.toString)

      // Delete the logged store if it is not valid.
      if (!isLoggedStoreValid(storeName, loggedStoreDir)) {
        info("Deleting logged storage partition directory %s." format loggedStoreDir.toPath.toString)
        Util.rm(loggedStoreDir)
      } else {
        val offset = readOffsetFile(loggedStoreDir)
        info("Read offset %s for the store %s from logged storage partition directory %s." format(offset, storeName, loggedStoreDir))
        fileOffset.put(new SystemStreamPartition(changeLogSystemStreams(storeName), partition), offset)
      }
    })
  }

  /**
    * Directory {@code loggedStoreDir} associated with the logged store {@code storeName} is valid,
    * if all of the following conditions are true.
    * a) If the store has to be persisted to disk.
    * b) If there is a valid offset file associated with the logged store.
    * c) If the logged store has not gone stale.
    *
    * @return true if the logged store is valid, false otherwise.
    */
  private def isLoggedStoreValid(storeName: String, loggedStoreDir: File): Boolean = {
    val changeLogDeleteRetentionInMs = changeLogDeleteRetentionsInMs.getOrElse(storeName,
                                                                               StorageConfig.DEFAULT_CHANGELOG_DELETE_RETENTION_MS)
    persistedStores.contains(storeName) && isOffsetFileValid(loggedStoreDir) &&
      !isStaleLoggedStore(loggedStoreDir, changeLogDeleteRetentionInMs)
  }

  /**
    * Determines if the logged store directory {@code loggedStoreDir} is stale. A store is stale if the following condition is true.
    *
    *  ((CurrentTime) - (LastModifiedTime of the Offset file) is greater than the changelog's tombstone retention).
    *
    * @param loggedStoreDir the base directory of the local change-logged store.
    * @param changeLogDeleteRetentionInMs the delete retention of the changelog in milli seconds.
    * @return true if the store is stale, false otherwise.
    *
    */
  private def isStaleLoggedStore(loggedStoreDir: File, changeLogDeleteRetentionInMs: Long): Boolean = {
    var isStaleStore = false
    val storePath = loggedStoreDir.toPath.toString
    if (loggedStoreDir.exists()) {
      val offsetFileRef = new File(loggedStoreDir, offsetFileName)
      val offsetFileLastModifiedTime = offsetFileRef.lastModified()
      if ((clock.currentTimeMillis() - offsetFileLastModifiedTime) >= changeLogDeleteRetentionInMs) {
        info ("Store: %s is stale since lastModifiedTime of offset file: %s, " +
          "is older than changelog deleteRetentionMs: %s." format(storePath, offsetFileLastModifiedTime, changeLogDeleteRetentionInMs))
        isStaleStore = true
      }
    } else {
      info("Logged storage partition directory: %s does not exist." format storePath)
    }
    isStaleStore
  }

  /**
    * An offset file associated with logged store {@code loggedStoreDir} is valid if it exists and is not empty.
    *
    * @return true if the offset file is valid. false otherwise.
    */
  private def isOffsetFileValid(loggedStoreDir: File): Boolean = {
    var hasValidOffsetFile = false
    if (loggedStoreDir.exists()) {
      val offsetContents = readOffsetFile(loggedStoreDir)
      if (offsetContents != null && !offsetContents.isEmpty) {
        hasValidOffsetFile = true
      } else {
        info("Offset file is not valid for store: %s." format loggedStoreDir.toPath.toString)
      }
    }
    hasValidOffsetFile
  }

  private def setupBaseDirs() {
    debug("Setting up base directories for stores.")
    taskStores.foreach {
      case (storeName, storageEngine) =>
        if (storageEngine.getStoreProperties.isLoggedStore) {
          val loggedStoragePartitionDir = TaskStorageManager.getStorePartitionDir(loggedStoreBaseDir, storeName, taskName)
          info("Using logged storage partition directory: %s for store: %s." format(loggedStoragePartitionDir.toPath.toString, storeName))
          if (!loggedStoragePartitionDir.exists()) loggedStoragePartitionDir.mkdirs()
        } else {
          val storagePartitionDir = TaskStorageManager.getStorePartitionDir(storeBaseDir, storeName, taskName)
          info("Using storage partition directory: %s for store: %s." format(storagePartitionDir.toPath.toString, storeName))
          storagePartitionDir.mkdirs()
        }
    }
  }

  /**
    * Read and return the contents of the offset file.
    *
    * @param loggedStoragePartitionDir the base directory of the store
    * @return the content of the offset file if it exists for the store, null otherwise.
    */
  private def readOffsetFile(loggedStoragePartitionDir: File): String = {
    var offset : String = null
    val offsetFileRef = new File(loggedStoragePartitionDir, offsetFileName)
    if (offsetFileRef.exists()) {
      info("Found offset file in logged storage partition directory: %s" format loggedStoragePartitionDir.toPath.toString)
      offset = Util.readDataFromFile(offsetFileRef)
    } else {
      info("No offset file found in logged storage partition directory: %s" format loggedStoragePartitionDir.toPath.toString)
    }
    offset
  }

  private def validateChangelogStreams() = {
    info("Validating change log streams")

    for ((storeName, systemStream) <- changeLogSystemStreams) {
      val systemAdmin = systemAdmins
        .getOrElse(systemStream.getSystem,
                   throw new SamzaException("Unable to get systemAdmin for store " + storeName + " and systemStream" + systemStream))
      systemAdmin.validateChangelogStream(systemStream.getStream, changeLogStreamPartitions)
    }

    val changeLogMetadata = streamMetadataCache.getStreamMetadata(changeLogSystemStreams.values.toSet)
    info("Got change log stream metadata: %s" format changeLogMetadata)

    changeLogOldestOffsets = getChangeLogOldestOffsetsForPartition(partition, changeLogMetadata)
    info("Assigning oldest change log offsets for taskName %s: %s" format (taskName, changeLogOldestOffsets))
  }

  private def startConsumers() {
    debug("Starting consumers for stores.")

    for ((storeName, systemStream) <- changeLogSystemStreams) {
      val systemStreamPartition = new SystemStreamPartition(systemStream, partition)
      val consumer = storeConsumers(storeName)
      val offset =
        Option(fileOffset.get(systemStreamPartition))
          .getOrElse(changeLogOldestOffsets
            .getOrElse(systemStream, throw new SamzaException("Missing a change log offset for %s." format systemStreamPartition)))

      if (offset != null) {
        info("Registering change log consumer with offset %s for %s." format (offset, systemStreamPartition))
        consumer.register(systemStreamPartition, offset)
      } else {
        info("Skipping change log restoration for %s because stream appears to be empty (offset was null)." format systemStreamPartition)
        taskStoresToRestore -= storeName
      }
    }

    storeConsumers.values.foreach(_.start)
  }

  private def restoreStores() {
    debug("Restoring stores.")

    for ((storeName, store) <- taskStoresToRestore) {
      if (changeLogSystemStreams.contains(storeName)) {
        val systemStream = changeLogSystemStreams(storeName)
        val systemStreamPartition = new SystemStreamPartition(systemStream, partition)
        val systemConsumer = storeConsumers(storeName)
        val systemConsumerIterator = new SystemStreamPartitionIterator(systemConsumer, systemStreamPartition)
        store.restore(systemConsumerIterator)
      }
    }
  }

  private def stopConsumers() {
    debug("Stopping consumers for stores.")

    storeConsumers.values.foreach(_.stop)
  }

  def flush() {
    debug("Flushing stores.")

    taskStores.values.foreach(_.flush)
    flushChangelogOffsetFiles()
  }

  def stopStores() {
    debug("Stopping stores.")
    taskStores.values.foreach(_.stop)
  }

  def stop() {
    stopStores()

    flushChangelogOffsetFiles()
  }

  /**
    * Writes the offset files for each changelog to disk.
    * These files are used when stores are restored from disk to determine whether
    * there is any new information in the changelog that is not reflected in the disk
    * copy of the store. If there is any delta, it is replayed from the changelog
    * e.g. This can happen if the job was run on this host, then another
    * host and back to this host.
    */
  private def flushChangelogOffsetFiles() {
    debug("Persisting logged key value stores")

    for ((storeName, systemStream) <- changeLogSystemStreams.filterKeys(storeName => persistedStores.contains(storeName))) {
      val systemAdmin = systemAdmins
              .getOrElse(systemStream.getSystem,
                         throw new SamzaException("Unable to get systemAdmin for store " + storeName + " and systemStream" + systemStream))

      debug("Fetching newest offset for store %s" format(storeName))
      try {
        val newestOffset = if (systemAdmin.isInstanceOf[ExtendedSystemAdmin]) {
          // This approach is much more efficient because it only fetches the newest offset for 1 SSP
          // rather than newest and oldest offsets for all SSPs. Use it if we can.
          systemAdmin.asInstanceOf[ExtendedSystemAdmin].getNewestOffset(new SystemStreamPartition(systemStream.getSystem, systemStream.getStream, partition), 3)
        } else {
          val streamToMetadata = systemAdmins(systemStream.getSystem)
                  .getSystemStreamMetadata(JavaConversions.setAsJavaSet(Set(systemStream.getStream)))
          val sspMetadata = streamToMetadata
                  .get(systemStream.getStream)
                  .getSystemStreamPartitionMetadata
                  .get(partition)
          sspMetadata.getNewestOffset
        }
        debug("Got offset %s for store %s" format(newestOffset, storeName))

        val offsetFile = new File(TaskStorageManager.getStorePartitionDir(loggedStoreBaseDir, storeName, taskName), offsetFileName)
        if (newestOffset != null) {
          debug("Storing offset for store in OFFSET file ")
          Util.writeDataToFile(offsetFile, newestOffset)
          debug("Successfully stored offset %s for store %s in OFFSET file " format(newestOffset, storeName))
        } else {
          //if newestOffset is null, then it means the store is (or has become) empty. No need to persist the offset file
          if (offsetFile.exists()) {
            Util.rm(offsetFile)
          }
          debug("Not storing OFFSET file for taskName %s. Store %s backed by changelog topic: %s, partition: %s is empty. " format (taskName, storeName, systemStream.getStream, partition.getPartitionId))
        }
      } catch {
        case e: Exception => error("Exception storing offset for store %s. Skipping." format(storeName), e)
      }

    }

    debug("Done persisting logged key value stores")
  }

  /**
   * Builds a map from SystemStreamPartition to oldest offset for changelogs.
   */
  private def getChangeLogOldestOffsetsForPartition(partition: Partition, inputStreamMetadata: Map[SystemStream, SystemStreamMetadata]): Map[SystemStream, String] = {
    inputStreamMetadata
      .mapValues(_.getSystemStreamPartitionMetadata.get(partition))
      .filter(_._2 != null)
      .mapValues(_.getOldestOffset)
  }
}
