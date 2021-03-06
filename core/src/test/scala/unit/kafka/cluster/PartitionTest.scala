/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kafka.cluster

import java.io.File
import java.nio.ByteBuffer
import java.util.Properties
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

import kafka.api.Request
import kafka.common.UnexpectedAppendOffsetException
import kafka.log.{CleanerConfig, LogConfig, LogManager}
import kafka.server._
import kafka.utils.{MockScheduler, MockTime, TestUtils}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.ReplicaNotAvailableException
import org.apache.kafka.common.metrics.Metrics
import org.apache.kafka.common.utils.Utils
import org.apache.kafka.common.record._
import org.apache.kafka.common.requests.LeaderAndIsrRequest
import org.junit.{After, Before, Test}
import org.junit.Assert._
import org.scalatest.Assertions.assertThrows

import scala.collection.JavaConverters._

class PartitionTest {

  val brokerId = 101
  val topicPartition = new TopicPartition("test-topic", 0)
  val time = new MockTime()
  val brokerTopicStats = new BrokerTopicStats
  val metrics = new Metrics

  var tmpDir: File = _
  var logDir1: File = _
  var logDir2: File = _
  var replicaManager: ReplicaManager = _
  var logManager: LogManager = _
  var logConfig: LogConfig = _

  @Before
  def setup(): Unit = {
    val logProps = new Properties()
    logProps.put(LogConfig.SegmentBytesProp, 512: java.lang.Integer)
    logProps.put(LogConfig.SegmentIndexBytesProp, 1000: java.lang.Integer)
    logProps.put(LogConfig.RetentionMsProp, 999: java.lang.Integer)
    logConfig = LogConfig(logProps)

    tmpDir = TestUtils.tempDir()
    logDir1 = TestUtils.randomPartitionLogDir(tmpDir)
    logDir2 = TestUtils.randomPartitionLogDir(tmpDir)
    logManager = TestUtils.createLogManager(
      logDirs = Seq(logDir1, logDir2), defaultConfig = logConfig, CleanerConfig(enableCleaner = false), time)
    logManager.startup()

    val brokerProps = TestUtils.createBrokerConfig(brokerId, TestUtils.MockZkConnect)
    brokerProps.put(KafkaConfig.LogDirsProp, Seq(logDir1, logDir2).map(_.getAbsolutePath).mkString(","))
    val brokerConfig = KafkaConfig.fromProps(brokerProps)
    replicaManager = new ReplicaManager(
      config = brokerConfig, metrics, time, zkClient = null, new MockScheduler(time),
      logManager, new AtomicBoolean(false), QuotaFactory.instantiate(brokerConfig, metrics, time, ""),
      brokerTopicStats, new MetadataCache(brokerId), new LogDirFailureChannel(brokerConfig.logDirs.size))
  }

  @After
  def tearDown(): Unit = {
    brokerTopicStats.close()
    metrics.close()

    logManager.shutdown()
    Utils.delete(tmpDir)
    logManager.liveLogDirs.foreach(Utils.delete)
    replicaManager.shutdown(checkpointHW = false)
  }

  @Test
  // Verify that partition.removeFutureLocalReplica() and partition.maybeReplaceCurrentWithFutureReplica() can run concurrently
  def testMaybeReplaceCurrentWithFutureReplica(): Unit = {
    val latch = new CountDownLatch(1)

    logManager.maybeUpdatePreferredLogDir(topicPartition, logDir1.getAbsolutePath)
    val log1 = logManager.getOrCreateLog(topicPartition, logConfig)
    logManager.maybeUpdatePreferredLogDir(topicPartition, logDir2.getAbsolutePath)
    val log2 = logManager.getOrCreateLog(topicPartition, logConfig, isFuture = true)
    val currentReplica = new Replica(brokerId, topicPartition, time, log = Some(log1))
    val futureReplica = new Replica(Request.FutureLocalReplicaId, topicPartition, time, log = Some(log2))
    val partition = new Partition(topicPartition.topic, topicPartition.partition, time, replicaManager)

    partition.addReplicaIfNotExists(futureReplica)
    partition.addReplicaIfNotExists(currentReplica)
    assertEquals(Some(currentReplica), partition.getReplica(brokerId))
    assertEquals(Some(futureReplica), partition.getReplica(Request.FutureLocalReplicaId))

    val thread1 = new Thread {
      override def run(): Unit = {
        latch.await()
        partition.removeFutureLocalReplica()
      }
    }

    val thread2 = new Thread {
      override def run(): Unit = {
        latch.await()
        partition.maybeReplaceCurrentWithFutureReplica()
      }
    }

    thread1.start()
    thread2.start()

    latch.countDown()
    thread1.join()
    thread2.join()
    assertEquals(None, partition.getReplica(Request.FutureLocalReplicaId))
  }

  @Test
  // Verify that replacement works when the replicas have the same log end offset but different base offsets in the
  // active segment
  def testMaybeReplaceCurrentWithFutureReplicaDifferentBaseOffsets(): Unit = {
    // Write records with duplicate keys to current replica and roll at offset 6
    logManager.maybeUpdatePreferredLogDir(topicPartition, logDir1.getAbsolutePath)
    val log1 = logManager.getOrCreateLog(topicPartition, logConfig)
    log1.appendAsLeader(MemoryRecords.withRecords(0L, CompressionType.NONE, 0,
      new SimpleRecord("k1".getBytes, "v1".getBytes),
      new SimpleRecord("k1".getBytes, "v2".getBytes),
      new SimpleRecord("k1".getBytes, "v3".getBytes),
      new SimpleRecord("k2".getBytes, "v4".getBytes),
      new SimpleRecord("k2".getBytes, "v5".getBytes),
      new SimpleRecord("k2".getBytes, "v6".getBytes)
    ), leaderEpoch = 0)
    log1.roll()
    log1.appendAsLeader(MemoryRecords.withRecords(0L, CompressionType.NONE, 0,
      new SimpleRecord("k3".getBytes, "v7".getBytes),
      new SimpleRecord("k4".getBytes, "v8".getBytes)
    ), leaderEpoch = 0)

    // Write to the future replica as if the log had been compacted, and do not roll the segment
    logManager.maybeUpdatePreferredLogDir(topicPartition, logDir2.getAbsolutePath)
    val log2 = logManager.getOrCreateLog(topicPartition, logConfig, isFuture = true)
    val buffer = ByteBuffer.allocate(1024)
    var builder = MemoryRecords.builder(buffer, RecordBatch.CURRENT_MAGIC_VALUE, CompressionType.NONE,
      TimestampType.CREATE_TIME, 0L, RecordBatch.NO_TIMESTAMP, 0)
    builder.appendWithOffset(2L, new SimpleRecord("k1".getBytes, "v3".getBytes))
    builder.appendWithOffset(5L, new SimpleRecord("k2".getBytes, "v6".getBytes))
    builder.appendWithOffset(6L, new SimpleRecord("k3".getBytes, "v7".getBytes))
    builder.appendWithOffset(7L, new SimpleRecord("k4".getBytes, "v8".getBytes))

    log2.appendAsFollower(builder.build())

    val currentReplica = new Replica(brokerId, topicPartition, time, log = Some(log1))
    val futureReplica = new Replica(Request.FutureLocalReplicaId, topicPartition, time, log = Some(log2))
    val partition = new Partition(topicPartition.topic(), topicPartition.partition(), time, replicaManager)

    partition.addReplicaIfNotExists(futureReplica)
    partition.addReplicaIfNotExists(currentReplica)
    assertEquals(Some(currentReplica), partition.getReplica(brokerId))
    assertEquals(Some(futureReplica), partition.getReplica(Request.FutureLocalReplicaId))

    assertTrue(partition.maybeReplaceCurrentWithFutureReplica())
  }

  @Test
  def testAppendRecordsAsFollowerBelowLogStartOffset(): Unit = {
    val log = logManager.getOrCreateLog(topicPartition, logConfig)
    val replica = new Replica(brokerId, topicPartition, time, log = Some(log))
    val partition = new Partition(topicPartition.topic, topicPartition.partition, time, replicaManager)
    partition.addReplicaIfNotExists(replica)
    assertEquals(Some(replica), partition.getReplica(replica.brokerId))

    val initialLogStartOffset = 5L
    partition.truncateFullyAndStartAt(initialLogStartOffset, isFuture = false)
    assertEquals(s"Log end offset after truncate fully and start at $initialLogStartOffset:",
                 initialLogStartOffset, replica.logEndOffset)
    assertEquals(s"Log start offset after truncate fully and start at $initialLogStartOffset:",
                 initialLogStartOffset, replica.logStartOffset)

    // verify that we cannot append records that do not contain log start offset even if the log is empty
    assertThrows[UnexpectedAppendOffsetException] {
      // append one record with offset = 3
      partition.appendRecordsToFollowerOrFutureReplica(createRecords(List(new SimpleRecord("k1".getBytes, "v1".getBytes)), baseOffset = 3L), isFuture = false)
    }
    assertEquals(s"Log end offset should not change after failure to append", initialLogStartOffset, replica.logEndOffset)

    // verify that we can append records that contain log start offset, even when first
    // offset < log start offset if the log is empty
    val newLogStartOffset = 4L
    val records = createRecords(List(new SimpleRecord("k1".getBytes, "v1".getBytes),
                                     new SimpleRecord("k2".getBytes, "v2".getBytes),
                                     new SimpleRecord("k3".getBytes, "v3".getBytes)),
                                baseOffset = newLogStartOffset)
    partition.appendRecordsToFollowerOrFutureReplica(records, isFuture = false)
    assertEquals(s"Log end offset after append of 3 records with base offset $newLogStartOffset:", 7L, replica.logEndOffset)
    assertEquals(s"Log start offset after append of 3 records with base offset $newLogStartOffset:", newLogStartOffset, replica.logStartOffset)

    // and we can append more records after that
    partition.appendRecordsToFollowerOrFutureReplica(createRecords(List(new SimpleRecord("k1".getBytes, "v1".getBytes)), baseOffset = 7L), isFuture = false)
    assertEquals(s"Log end offset after append of 1 record at offset 7:", 8L, replica.logEndOffset)
    assertEquals(s"Log start offset not expected to change:", newLogStartOffset, replica.logStartOffset)

    // but we cannot append to offset < log start if the log is not empty
    assertThrows[UnexpectedAppendOffsetException] {
      val records2 = createRecords(List(new SimpleRecord("k1".getBytes, "v1".getBytes),
                                        new SimpleRecord("k2".getBytes, "v2".getBytes)),
                                   baseOffset = 3L)
      partition.appendRecordsToFollowerOrFutureReplica(records2, isFuture = false)
    }
    assertEquals(s"Log end offset should not change after failure to append", 8L, replica.logEndOffset)

    // we still can append to next offset
    partition.appendRecordsToFollowerOrFutureReplica(createRecords(List(new SimpleRecord("k1".getBytes, "v1".getBytes)), baseOffset = 8L), isFuture = false)
    assertEquals(s"Log end offset after append of 1 record at offset 8:", 9L, replica.logEndOffset)
    assertEquals(s"Log start offset not expected to change:", newLogStartOffset, replica.logStartOffset)
  }

  @Test
  def testGetReplica(): Unit = {
    val log = logManager.getOrCreateLog(topicPartition, logConfig)
    val replica = new Replica(brokerId, topicPartition, time, log = Some(log))
    val partition = new
        Partition(topicPartition.topic, topicPartition.partition, time, replicaManager)

    assertEquals(None, partition.getReplica(brokerId))
    assertThrows[ReplicaNotAvailableException] {
      partition.getReplicaOrException(brokerId)
    }

    partition.addReplicaIfNotExists(replica)
    assertEquals(replica, partition.getReplicaOrException(brokerId))
  }

  @Test
  def testAppendRecordsToFollowerWithNoReplicaThrowsException(): Unit = {
    val partition = new Partition(topicPartition.topic, topicPartition.partition, time, replicaManager)
    assertThrows[ReplicaNotAvailableException] {
      partition.appendRecordsToFollowerOrFutureReplica(
           createRecords(List(new SimpleRecord("k1".getBytes, "v1".getBytes)), baseOffset = 0L), isFuture = false)
    }
  }

  @Test
  def testMakeFollowerWithNoLeaderIdChange(): Unit = {
    val partition = new Partition(topicPartition.topic, topicPartition.partition, time, replicaManager)

    // Start off as follower
    var partitionStateInfo = new LeaderAndIsrRequest.PartitionState(0, 1, 1, List[Integer](0, 1, 2).asJava, 1, List[Integer](0, 1, 2).asJava, false)
    partition.makeFollower(0, partitionStateInfo, 0)

    // Request with same leader and epoch increases by more than 1, perform become-follower steps
    partitionStateInfo = new LeaderAndIsrRequest.PartitionState(0, 1, 3, List[Integer](0, 1, 2).asJava, 1, List[Integer](0, 1, 2).asJava, false)
    assertTrue(partition.makeFollower(0, partitionStateInfo, 1))

    // Request with same leader and epoch increases by only 1, skip become-follower steps
    partitionStateInfo = new LeaderAndIsrRequest.PartitionState(0, 1, 4, List[Integer](0, 1, 2).asJava, 1, List[Integer](0, 1, 2).asJava, false)
    assertFalse(partition.makeFollower(0, partitionStateInfo, 2))

    // Request with same leader and same epoch, skip become-follower steps
    partitionStateInfo = new LeaderAndIsrRequest.PartitionState(0, 1, 4, List[Integer](0, 1, 2).asJava, 1, List[Integer](0, 1, 2).asJava, false)
    assertFalse(partition.makeFollower(0, partitionStateInfo, 2))
  }

  def createRecords(records: Iterable[SimpleRecord], baseOffset: Long, partitionLeaderEpoch: Int = 0): MemoryRecords = {
    val buf = ByteBuffer.allocate(DefaultRecordBatch.sizeInBytes(records.asJava))
    val builder = MemoryRecords.builder(
      buf, RecordBatch.CURRENT_MAGIC_VALUE, CompressionType.NONE, TimestampType.LOG_APPEND_TIME,
      baseOffset, time.milliseconds, partitionLeaderEpoch)
    records.foreach(builder.append)
    builder.build()
  }

}
