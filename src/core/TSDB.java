// This file is part of OpenTSDB.
// Copyright (C) 2010-2012  The OpenTSDB Authors.
//
// This program is free software: you can redistribute it and/or modify it
// under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 2.1 of the License, or (at your
// option) any later version.  This program is distributed in the hope that it
// will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
// of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser
// General Public License for more details.  You should have received a copy
// of the GNU Lesser General Public License along with this program.  If not,
// see <http://www.gnu.org/licenses/>.
package net.opentsdb.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.stumbleupon.async.Callback;
import com.stumbleupon.async.Deferred;
import com.stumbleupon.async.DeferredGroupException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.hbase.async.Bytes;
import org.hbase.async.ClientStats;
import org.hbase.async.DeleteRequest;
import org.hbase.async.GetRequest;
import org.hbase.async.HBaseClient;
import org.hbase.async.HBaseException;
import org.hbase.async.KeyValue;
import org.hbase.async.PutRequest;

import net.opentsdb.uid.NoSuchUniqueName;
import net.opentsdb.uid.UniqueId;
import net.opentsdb.uid.UniqueId.UniqueIdType;
import net.opentsdb.utils.Config;
import net.opentsdb.utils.DateTime;
import net.opentsdb.stats.Histogram;
import net.opentsdb.stats.StatsCollector;

/**
 * Thread-safe implementation of the TSDB client.
 * <p>
 * This class is the central class of OpenTSDB.  You use it to add new data
 * points or query the database.
 */
public final class TSDB {
  private static final Logger LOG = LoggerFactory.getLogger(TSDB.class);
  
  static final byte[] FAMILY = { 't' };

  private static final String METRICS_QUAL = "metrics";
  private static final short METRICS_WIDTH = 3;
  private static final String TAG_NAME_QUAL = "tagk";
  private static final short TAG_NAME_WIDTH = 3;
  private static final String TAG_VALUE_QUAL = "tagv";
  private static final short TAG_VALUE_WIDTH = 3;

  /** Client for the HBase cluster to use.  */
  final HBaseClient client;

  /** Name of the table in which timeseries are stored.  */
  final byte[] table;
  /** Name of the table in which UID information is stored. */
  final byte[] uidtable;

  /** Unique IDs for the metric names. */
  final UniqueId metrics;
  /** Unique IDs for the tag names. */
  final UniqueId tag_names;
  /** Unique IDs for the tag values. */
  final UniqueId tag_values;

  /** Configuration object for all TSDB components */
  final Config config;

  /**
   * Row keys that need to be compacted.
   * Whenever we write a new data point to a row, we add the row key to this
   * set.  Every once in a while, the compaction thread will go through old
   * row keys and will read re-compact them.
   */
  private final CompactionQueue compactionq;

  /**
   * Constructor
   * @param config An initialized configuration object
   * @since 2.0
   */
  public TSDB(final Config config) {
    this.config = config;
    this.client = new HBaseClient(
        config.getString("tsd.storage.hbase.zk_quorum"),
        config.getString("tsd.storage.hbase.zk_basedir"));
    this.client.setFlushInterval(config.getShort("tsd.storage.flush_interval"));
    table = config.getString("tsd.storage.hbase.data_table").getBytes();
    uidtable = config.getString("tsd.storage.hbase.uid_table").getBytes();

    metrics = new UniqueId(client, uidtable, METRICS_QUAL, METRICS_WIDTH);
    tag_names = new UniqueId(client, uidtable, TAG_NAME_QUAL, TAG_NAME_WIDTH);
    tag_values = new UniqueId(client, uidtable, TAG_VALUE_QUAL, TAG_VALUE_WIDTH);
    compactionq = new CompactionQueue(this);

    if (config.hasProperty("tsd.core.timezone"))
      DateTime.setDefaultTimezone(config.getString("tsd.core.timezone"));
    
    LOG.debug(config.dumpConfiguration());
  }
  
  /** 
   * Returns the configured HBase client 
   * @return The HBase client
   * @since 2.0 
   */
  public final HBaseClient getClient() {
    return this.client;
  }
  
  /** 
   * Getter that returns the configuration object
   * @return The configuration object
   * @since 2.0 
   */
  public final Config getConfig() {
    return this.config;
  }

  /**
   * Attempts to find the name for a unique identifier given a type
   * @param type The type of UID
   * @param uid The UID to search for
   * @return The name of the UID object if found
   * @throws IllegalArgumentException if the type is not valid
   * @throws NoSuchUniqueId if the UID was not found
   * @since 2.0
   */
  public String getUidName(final UniqueIdType type, final byte[] uid) {
    if (uid == null) {
      throw new IllegalArgumentException("Missing UID");
    }
    switch (type) {
      case METRIC:
        return this.metrics.getName(uid);
      case TAGK:
        return this.tag_names.getName(uid);
      case TAGV:
        return this.tag_values.getName(uid);
      default:
        throw new IllegalArgumentException("Unrecognized UID type");
    }
  }
  
  /**
   * Attempts to find the UID matching a given name
   * @param type The type of UID
   * @param name The name to search for
   * @throws IllegalArgumentException if the type is not valid
   * @throws NoSuchUniqueName if the name was not found
   * @since 2.0
   */
  public byte[] getUID(final UniqueIdType type, final String name) {
    if (name == null || name.isEmpty()) {
      throw new IllegalArgumentException("Missing UID name");
    }
    switch (type) {
      case METRIC:
        return this.metrics.getId(name);
      case TAGK:
        return this.tag_names.getId(name);
      case TAGV:
        return this.tag_values.getId(name);
      default:
        throw new IllegalArgumentException("Unrecognized UID type");
    }
  }
  
  /**
   * Verifies that the data and UID tables exist in HBase
   * @return An ArrayList of objects to wait for
   * @throws TableNotFoundException
   * @since 2.0
   */
  public Deferred<ArrayList<Object>> checkNecessaryTablesExist() {
    return Deferred.group(client.ensureTableExists(
        config.getString("tsd.storage.hbase.data_table")),
        client.ensureTableExists(
            config.getString("tsd.storage.hbase.uid_table")));
  }
  
  /** Number of cache hits during lookups involving UIDs. */
  public int uidCacheHits() {
    return (metrics.cacheHits() + tag_names.cacheHits()
            + tag_values.cacheHits());
  }

  /** Number of cache misses during lookups involving UIDs. */
  public int uidCacheMisses() {
    return (metrics.cacheMisses() + tag_names.cacheMisses()
            + tag_values.cacheMisses());
  }

  /** Number of cache entries currently in RAM for lookups involving UIDs. */
  public int uidCacheSize() {
    return (metrics.cacheSize() + tag_names.cacheSize()
            + tag_values.cacheSize());
  }

  /**
   * Collects the stats and metrics tracked by this instance.
   * @param collector The collector to use.
   */
  public void collectStats(final StatsCollector collector) {
    collectUidStats(metrics, collector);
    collectUidStats(tag_names, collector);
    collectUidStats(tag_values, collector);

    {
      final Runtime runtime = Runtime.getRuntime();
      collector.record("jvm.ramfree", runtime.freeMemory());
      collector.record("jvm.ramused", runtime.totalMemory());
    }

    collector.addExtraTag("class", "IncomingDataPoints");
    try {
      collector.record("hbase.latency", IncomingDataPoints.putlatency, "method=put");
    } finally {
      collector.clearExtraTag("class");
    }

    collector.addExtraTag("class", "TsdbQuery");
    try {
      collector.record("hbase.latency", TsdbQuery.scanlatency, "method=scan");
    } finally {
      collector.clearExtraTag("class");
    }
    final ClientStats stats = client.stats();
    collector.record("hbase.root_lookups", stats.rootLookups());
    collector.record("hbase.meta_lookups",
                     stats.uncontendedMetaLookups(), "type=uncontended");
    collector.record("hbase.meta_lookups",
                     stats.contendedMetaLookups(), "type=contended");
    collector.record("hbase.rpcs",
                     stats.atomicIncrements(), "type=increment");
    collector.record("hbase.rpcs", stats.deletes(), "type=delete");
    collector.record("hbase.rpcs", stats.gets(), "type=get");
    collector.record("hbase.rpcs", stats.puts(), "type=put");
    collector.record("hbase.rpcs", stats.rowLocks(), "type=rowLock");
    collector.record("hbase.rpcs", stats.scannersOpened(), "type=openScanner");
    collector.record("hbase.rpcs", stats.scans(), "type=scan");
    collector.record("hbase.rpcs.batched", stats.numBatchedRpcSent());
    collector.record("hbase.flushes", stats.flushes());
    collector.record("hbase.connections.created", stats.connectionsCreated());
    collector.record("hbase.nsre", stats.noSuchRegionExceptions());
    collector.record("hbase.nsre.rpcs_delayed",
                     stats.numRpcDelayedDueToNSRE());

    compactionq.collectStats(collector);
  }

  /** Returns a latency histogram for Put RPCs used to store data points. */
  public Histogram getPutLatencyHistogram() {
    return IncomingDataPoints.putlatency;
  }

  /** Returns a latency histogram for Scan RPCs used to fetch data points.  */
  public Histogram getScanLatencyHistogram() {
    return TsdbQuery.scanlatency;
  }

  /**
   * Collects the stats for a {@link UniqueId}.
   * @param uid The instance from which to collect stats.
   * @param collector The collector to use.
   */
  private static void collectUidStats(final UniqueId uid,
                                      final StatsCollector collector) {
    collector.record("uid.cache-hit", uid.cacheHits(), "kind=" + uid.kind());
    collector.record("uid.cache-miss", uid.cacheMisses(), "kind=" + uid.kind());
    collector.record("uid.cache-size", uid.cacheSize(), "kind=" + uid.kind());
  }

  /**
   * Returns a new {@link Query} instance suitable for this TSDB.
   */
  public Query newQuery() {
    return new TsdbQuery(this);
  }

  /**
   * Returns a new {@link WritableDataPoints} instance suitable for this TSDB.
   * <p>
   * If you want to add a single data-point, consider using {@link #addPoint}
   * instead.
   */
  public WritableDataPoints newDataPoints() {
    return new IncomingDataPoints(this);
  }

  /**
   * Adds a single integer value data point in the TSDB.
   * @param metric A non-empty string.
   * @param timestamp The timestamp associated with the value.
   * @param value The value of the data point.
   * @param tags The tags on this series.  This map must be non-empty.
   * @return A deferred object that indicates the completion of the request.
   * The {@link Object} has not special meaning and can be {@code null} (think
   * of it as {@code Deferred<Void>}). But you probably want to attach at
   * least an errback to this {@code Deferred} to handle failures.
   * @throws IllegalArgumentException if the timestamp is less than or equal
   * to the previous timestamp added or 0 for the first timestamp, or if the
   * difference with the previous timestamp is too large.
   * @throws IllegalArgumentException if the metric name is empty or contains
   * illegal characters.
   * @throws IllegalArgumentException if the tags list is empty or one of the
   * elements contains illegal characters.
   * @throws HBaseException (deferred) if there was a problem while persisting
   * data.
   */
  public Deferred<Object> addPoint(final String metric,
                                   final long timestamp,
                                   final long value,
                                   final Map<String, String> tags) {
    final short flags = 0x7;  // An int stored on 8 bytes.
    return addPointInternal(metric, timestamp, Bytes.fromLong(value),
                            tags, flags);
  }

  /**
   * Adds a single floating-point value data point in the TSDB.
   * @param metric A non-empty string.
   * @param timestamp The timestamp associated with the value.
   * @param value The value of the data point.
   * @param tags The tags on this series.  This map must be non-empty.
   * @return A deferred object that indicates the completion of the request.
   * The {@link Object} has not special meaning and can be {@code null} (think
   * of it as {@code Deferred<Void>}). But you probably want to attach at
   * least an errback to this {@code Deferred} to handle failures.
   * @throws IllegalArgumentException if the timestamp is less than or equal
   * to the previous timestamp added or 0 for the first timestamp, or if the
   * difference with the previous timestamp is too large.
   * @throws IllegalArgumentException if the metric name is empty or contains
   * illegal characters.
   * @throws IllegalArgumentException if the value is NaN or infinite.
   * @throws IllegalArgumentException if the tags list is empty or one of the
   * elements contains illegal characters.
   * @throws HBaseException (deferred) if there was a problem while persisting
   * data.
   */
  public Deferred<Object> addPoint(final String metric,
                                   final long timestamp,
                                   final float value,
                                   final Map<String, String> tags) {
    if (Float.isNaN(value) || Float.isInfinite(value)) {
      throw new IllegalArgumentException("value is NaN or Infinite: " + value
                                         + " for metric=" + metric
                                         + " timestamp=" + timestamp);
    }
    final short flags = Const.FLAG_FLOAT | 0x3;  // A float stored on 4 bytes.
    return addPointInternal(metric, timestamp,
                            Bytes.fromInt(Float.floatToRawIntBits(value)),
                            tags, flags);
  }

  private Deferred<Object> addPointInternal(final String metric,
                                            final long timestamp,
                                            final byte[] value,
                                            final Map<String, String> tags,
                                            final short flags) {
    if ((timestamp & 0xFFFFFFFF00000000L) != 0) {
      // => timestamp < 0 || timestamp > Integer.MAX_VALUE
      throw new IllegalArgumentException((timestamp < 0 ? "negative " : "bad")
          + " timestamp=" + timestamp
          + " when trying to add value=" + Arrays.toString(value) + '/' + flags
          + " to metric=" + metric + ", tags=" + tags);
    }

    IncomingDataPoints.checkMetricAndTags(metric, tags);
    final byte[] row = IncomingDataPoints.rowKeyTemplate(this, metric, tags);
    final long base_time = (timestamp - (timestamp % Const.MAX_TIMESPAN));
    Bytes.setInt(row, (int) base_time, metrics.width());
    scheduleForCompaction(row, (int) base_time);
    final short qualifier = (short) ((timestamp - base_time) << Const.FLAG_BITS
                                     | flags);
    final PutRequest point = new PutRequest(table, row, FAMILY,
                                            Bytes.fromShort(qualifier), value);
    // TODO(tsuna): Add a callback to time the latency of HBase and store the
    // timing in a moving Histogram (once we have a class for this).
    return client.put(point);
  }

  /**
   * Forces a flush of any un-committed in memory data.
   * <p>
   * For instance, any data point not persisted will be sent to HBase.
   * @return A {@link Deferred} that will be called once all the un-committed
   * data has been successfully and durably stored.  The value of the deferred
   * object return is meaningless and unspecified, and can be {@code null}.
   * @throws HBaseException (deferred) if there was a problem sending
   * un-committed data to HBase.  Please refer to the {@link HBaseException}
   * hierarchy to handle the possible failures.  Some of them are easily
   * recoverable by retrying, some are not.
   */
  public Deferred<Object> flush() throws HBaseException {
    return client.flush();
  }

  /**
   * Gracefully shuts down this instance.
   * <p>
   * This does the same thing as {@link #flush} and also releases all other
   * resources.
   * @return A {@link Deferred} that will be called once all the un-committed
   * data has been successfully and durably stored, and all resources used by
   * this instance have been released.  The value of the deferred object
   * return is meaningless and unspecified, and can be {@code null}.
   * @throws HBaseException (deferred) if there was a problem sending
   * un-committed data to HBase.  Please refer to the {@link HBaseException}
   * hierarchy to handle the possible failures.  Some of them are easily
   * recoverable by retrying, some are not.
   */
  public Deferred<Object> shutdown() {
    final class HClientShutdown implements Callback<Object, ArrayList<Object>> {
      public Object call(final ArrayList<Object> args) {
        return client.shutdown();
      }
      public String toString() {
        return "shutdown HBase client";
      }
    }
    final class ShutdownErrback implements Callback<Object, Exception> {
      public Object call(final Exception e) {
        final Logger LOG = LoggerFactory.getLogger(ShutdownErrback.class);
        if (e instanceof DeferredGroupException) {
          final DeferredGroupException ge = (DeferredGroupException) e;
          for (final Object r : ge.results()) {
            if (r instanceof Exception) {
              LOG.error("Failed to flush the compaction queue", (Exception) r);
            }
          }
        } else {
          LOG.error("Failed to flush the compaction queue", e);
        }
        return client.shutdown();
      }
      public String toString() {
        return "shutdown HBase client after error";
      }
    }
    // First flush the compaction queue, then shutdown the HBase client.
    return config.enable_compactions()
      ? compactionq.flush().addCallbacks(new HClientShutdown(),
                                         new ShutdownErrback())
      : client.shutdown();
  }

  /**
   * Given a prefix search, returns a few matching metric names.
   * @param search A prefix to search.
   */
  public List<String> suggestMetrics(final String search) {
    return metrics.suggest(search);
  }
  
  /**
   * Given a prefix search, returns matching metric names.
   * @param search A prefix to search.
   * @param max_results Maximum number of results to return.
   * @since 2.0
   */
  public List<String> suggestMetrics(final String search, 
      final int max_results) {
    return metrics.suggest(search, max_results);
  }

  /**
   * Given a prefix search, returns a few matching tag names.
   * @param search A prefix to search.
   */
  public List<String> suggestTagNames(final String search) {
    return tag_names.suggest(search);
  }
  
  /**
   * Given a prefix search, returns matching tagk names.
   * @param search A prefix to search.
   * @param max_results Maximum number of results to return.
   * @since 2.0
   */
  public List<String> suggestTagNames(final String search, 
      final int max_results) {
    return tag_names.suggest(search, max_results);
  }

  /**
   * Given a prefix search, returns a few matching tag values.
   * @param search A prefix to search.
   */
  public List<String> suggestTagValues(final String search) {
    return tag_values.suggest(search);
  }
  
  /**
   * Given a prefix search, returns matching tag values.
   * @param search A prefix to search.
   * @param max_results Maximum number of results to return.
   * @since 2.0
   */
  public List<String> suggestTagValues(final String search, 
      final int max_results) {
    return tag_values.suggest(search, max_results);
  }

  /**
   * Discards all in-memory caches.
   * @since 1.1
   */
  public void dropCaches() {
    metrics.dropCaches();
    tag_names.dropCaches();
    tag_values.dropCaches();
  }

  /**
   * Attempts to assign a UID to a name for the given type
   * Used by the UniqueIdRpc call to generate IDs for new metrics, tagks or 
   * tagvs. The name must pass validation and if it's already assigned a UID,
   * this method will throw an error with the proper UID. Otherwise if it can
   * create the UID, it will be returned
   * @param type The type of uid to assign, metric, tagk or tagv
   * @param name The name of the uid object
   * @return A byte array with the UID if the assignment was successful
   * @throws IllegalArgumentException if the name is invalid or it already 
   * exists
   * @2.0
   */
  public byte[] assignUid(final String type, final String name) {
    Tags.validateString(type, name);
    if (type.toLowerCase().equals("metric")) {
      try {
        final byte[] uid = this.metrics.getId(name);
        throw new IllegalArgumentException("Name already exists with UID: " +
            UniqueId.uidToString(uid));
      } catch (NoSuchUniqueName nsue) {
        return this.metrics.getOrCreateId(name);
      }
    } else if (type.toLowerCase().equals("tagk")) {
      try {
        final byte[] uid = this.tag_names.getId(name);
        throw new IllegalArgumentException("Name already exists with UID: " +
            UniqueId.uidToString(uid));
      } catch (NoSuchUniqueName nsue) {
        return this.tag_names.getOrCreateId(name);
      }
    } else if (type.toLowerCase().equals("tagv")) {
      try {
        final byte[] uid = this.tag_values.getId(name);
        throw new IllegalArgumentException("Name already exists with UID: " +
            UniqueId.uidToString(uid));
      } catch (NoSuchUniqueName nsue) {
        return this.tag_values.getOrCreateId(name);
      }
    } else {
      LOG.warn("Unknown type name: " + type);
      throw new IllegalArgumentException("Unknown type name");
    }
  }
  
  /** @return the name of the UID table as a byte array for client requests */
  public byte[] uidTable() {
    return this.uidtable;
  }
  
  /** @return the name of the data table as a byte array for client requests */
  public byte[] dataTable() {
    return this.table;
  }
  
  // ------------------ //
  // Compaction helpers //
  // ------------------ //

  final KeyValue compact(final ArrayList<KeyValue> row) {
    return compactionq.compact(row);
  }

  /**
   * Schedules the given row key for later re-compaction.
   * Once this row key has become "old enough", we'll read back all the data
   * points in that row, write them back to HBase in a more compact fashion,
   * and delete the individual data points.
   * @param row The row key to re-compact later.  Will not be modified.
   * @param base_time The 32-bit unsigned UNIX timestamp.
   */
  final void scheduleForCompaction(final byte[] row, final int base_time) {
    if (config.enable_compactions()) {
      compactionq.add(row);
    }
  }

  // ------------------------ //
  // HBase operations helpers //
  // ------------------------ //

  /** Gets the entire given row from the data table. */
  final Deferred<ArrayList<KeyValue>> get(final byte[] key) {
    return client.get(new GetRequest(table, key));
  }

  /** Puts the given value into the data table. */
  final Deferred<Object> put(final byte[] key,
                             final byte[] qualifier,
                             final byte[] value) {
    return client.put(new PutRequest(table, key, FAMILY, qualifier, value));
  }

  /** Deletes the given cells from the data table. */
  final Deferred<Object> delete(final byte[] key, final byte[][] qualifiers) {
    return client.delete(new DeleteRequest(table, key, FAMILY, qualifiers));
  }

}
