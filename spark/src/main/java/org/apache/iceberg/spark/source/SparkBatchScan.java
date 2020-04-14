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

package org.apache.iceberg.spark.source;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import com.google.common.collect.Lists;
import java.io.Closeable;
import java.io.IOException;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.iceberg.CombinedScanTask;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataTask;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.PartitionField;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.SchemaParser;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableProperties;
import org.apache.iceberg.TableScan;
import org.apache.iceberg.avro.Avro;
import org.apache.iceberg.common.DynMethods;
import org.apache.iceberg.encryption.EncryptedFiles;
import org.apache.iceberg.encryption.EncryptionManager;
import org.apache.iceberg.exceptions.RuntimeIOException;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.io.FileIO;
import org.apache.iceberg.io.InputFile;
import org.apache.iceberg.orc.ORC;
import org.apache.iceberg.parquet.Parquet;
import org.apache.iceberg.spark.SparkSchemaUtil;
import org.apache.iceberg.spark.SparkUtil;
import org.apache.iceberg.spark.data.SparkAvroReader;
import org.apache.iceberg.spark.data.SparkOrcReader;
import org.apache.iceberg.spark.data.SparkParquetReaders;
import org.apache.iceberg.types.TypeUtil;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.ByteBuffers;
import org.apache.spark.rdd.InputFileBlockHolder;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.expressions.Attribute;
import org.apache.spark.sql.catalyst.expressions.AttributeReference;
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow;
import org.apache.spark.sql.catalyst.expressions.JoinedRow;
import org.apache.spark.sql.catalyst.expressions.UnsafeProjection;
import org.apache.spark.sql.connector.catalog.MetadataColumn;
import org.apache.spark.sql.connector.read.Batch;
import org.apache.spark.sql.connector.read.InputPartition;
import org.apache.spark.sql.connector.read.PartitionReader;
import org.apache.spark.sql.connector.read.PartitionReaderFactory;
import org.apache.spark.sql.connector.read.Scan;
import org.apache.spark.sql.connector.read.Statistics;
import org.apache.spark.sql.connector.read.SupportsReportStatistics;
import org.apache.spark.sql.types.BinaryType;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.Decimal;
import org.apache.spark.sql.types.DecimalType;
import org.apache.spark.sql.types.StringType;
import org.apache.spark.sql.types.StructField;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.sql.util.CaseInsensitiveStringMap;
import org.apache.spark.unsafe.types.UTF8String;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.collection.JavaConverters;

class SparkBatchScan implements Scan, Batch, SupportsReportStatistics {
  private static final Logger LOG = LoggerFactory.getLogger(SparkBatchScan.class);
  private static final Cache<CacheKey, List<CombinedScanTask>> PLANNING_CACHE = Caffeine
      .newBuilder()
      .softValues()
      .build();

  private final Table table;
  private final boolean caseSensitive;
  private final Schema expectedSchema;
  private final String[] metaColumns;
  private final List<Expression> filterExpressions;
  private final Long snapshotId;
  private final Long asOfTimestamp;
  private final Long splitSize;
  private final Integer splitLookback;
  private final Long splitOpenFileCost;
  private final FileIO fileIo;
  private final EncryptionManager encryptionManager;

  // lazy variables
  private List<CombinedScanTask> tasks = null; // lazy cache of tasks

  SparkBatchScan(Table table, boolean caseSensitive, Schema expectedSchema, String[] metaColumns,
                 List<Expression> filters, CaseInsensitiveStringMap options) {
    this.table = table;
    this.caseSensitive = caseSensitive;
    this.expectedSchema = expectedSchema;
    this.metaColumns = metaColumns;
    this.filterExpressions = filters;
    this.snapshotId = options.containsKey("snapshot-id") ? options.getLong("snapshot-id", 0) : null;
    this.asOfTimestamp = options.containsKey("as-of-timestamp") ? options.getLong("as-of-timestamp", 0) : null;

    if (snapshotId != null && asOfTimestamp != null) {
      throw new IllegalArgumentException(
          "Cannot scan using both snapshot-id and as-of-timestamp to select the table snapshot");
    }

    // look for split behavior overrides in options
    this.splitSize = options.containsKey("split-size") ? options.getLong("split-size",
        TableProperties.SPLIT_SIZE_DEFAULT) : null;
    this.splitLookback = options.containsKey("lookback") ? options.getInt("lookback",
        TableProperties.SPLIT_LOOKBACK_DEFAULT) : null;
    this.splitOpenFileCost = options.containsKey("file-open-cost") ? options.getLong("file-open-cost",
        TableProperties.SPLIT_OPEN_FILE_COST_DEFAULT) : null;

    this.fileIo = table.io();
    this.encryptionManager = table.encryption();
  }

  @Override
  public Batch toBatch() {
    return this;
  }

  @Override
  public StructType readSchema() {
    StructType sparkSchema = SparkSchemaUtil.convert(expectedSchema);
    if (metaColumns.length < 1) {
      return sparkSchema;
    } else {
      return MetadataColumns.addMetadataColumns(sparkSchema, metaColumns);
    }
  }

  @Override
  public InputPartition[] planInputPartitions() {
    String tableSchemaString = SchemaParser.toJson(table.schema());
    String expectedSchemaString = SchemaParser.toJson(expectedSchema);

    List<CombinedScanTask> scanTasks = tasks();
    InputPartition[] readTasks = new InputPartition[scanTasks.size()];
    for (int i = 0; i < scanTasks.size(); i++) {
      readTasks[i] = new ReadTask(
          scanTasks.get(i), tableSchemaString, expectedSchemaString, metaColumns, fileIo, encryptionManager,
          caseSensitive);
    }

    return readTasks;
  }

  @Override
  public ReaderFactory createReaderFactory() {
    return new ReaderFactory();
  }

  @Override
  public Statistics estimateStatistics() {
    long sizeInBytes = 0L;
    long numRows = 0L;

    for (CombinedScanTask task : tasks()) {
      for (FileScanTask file : task.files()) {
        sizeInBytes += file.length();
        numRows += file.file().recordCount();
      }
    }

    return new Stats(sizeInBytes, numRows);
  }

  private List<CombinedScanTask> tasks() {
    if (tasks == null) {
      TableScan scan = table
          .newScan()
          .caseSensitive(caseSensitive)
          .project(expectedSchema);

      if (snapshotId != null) {
        scan = scan.useSnapshot(snapshotId);
      }

      if (asOfTimestamp != null) {
        scan = scan.asOfTime(asOfTimestamp);
      }

      if (splitSize != null) {
        scan = scan.option(TableProperties.SPLIT_SIZE, splitSize.toString());
      }

      if (splitLookback != null) {
        scan = scan.option(TableProperties.SPLIT_LOOKBACK, splitLookback.toString());
      }

      if (splitOpenFileCost != null) {
        scan = scan.option(TableProperties.SPLIT_OPEN_FILE_COST, splitOpenFileCost.toString());
      }

      if (filterExpressions != null) {
        for (Expression filter : filterExpressions) {
          scan = scan.filter(filter);
        }
      }

      TableScan finalScan = scan;
      CacheKey key = CacheKey.from(scan, splitSize);
      this.tasks = PLANNING_CACHE.get(key, ignored -> {
        LOG.info("Planning tasks for {} (no cached tasks available)", this);
        try (CloseableIterable<CombinedScanTask> tasksIterable = finalScan.planTasks()) {
          return Lists.newArrayList(tasksIterable);
        }  catch (IOException e) {
          throw new RuntimeIOException(e, "Failed to close table scan: %s", finalScan);
        }
      });
    }

    return tasks;
  }

  @Override
  public String description() {
    String filters = filterExpressions.stream().map(SparkUtil::describe).collect(Collectors.joining(", "));
    return String.format("%s [filters=%s]", table, filters);
  }

  @Override
  public String toString() {
    return String.format(
        "IcebergScan(table=%s, type=%s, filters=%s, caseSensitive=%s)",
        table, expectedSchema.asStruct(), filterExpressions, caseSensitive);
  }

  private static class ReaderFactory implements PartitionReaderFactory {
    @Override
    public PartitionReader<InternalRow> createReader(InputPartition inputPartition) {
      if (inputPartition instanceof ReadTask) {
        return new TaskDataReader((ReadTask) inputPartition);
      } else {
        throw new UnsupportedOperationException("Incorrect input partition type: " + inputPartition);
      }
    }
  }

  private static class ReadTask implements InputPartition, Serializable {
    private final CombinedScanTask task;
    private final String tableSchemaString;
    private final String expectedSchemaString;
    private final String[] metaColumns;
    private final FileIO io;
    private final EncryptionManager encryptionManager;
    private final boolean caseSensitive;

    private transient Schema tableSchema = null;
    private transient Schema expectedSchema = null;

    ReadTask(CombinedScanTask task, String tableSchemaString, String expectedSchemaString, String[] metaColumns,
             FileIO io, EncryptionManager encryptionManager, boolean caseSensitive) {
      this.task = task;
      this.tableSchemaString = tableSchemaString;
      this.expectedSchemaString = expectedSchemaString;
      this.metaColumns = metaColumns;
      this.io = io;
      this.encryptionManager = encryptionManager;
      this.caseSensitive = caseSensitive;
    }

    public Collection<FileScanTask> files() {
      return task.files();
    }

    public FileIO io() {
      return io;
    }

    public EncryptionManager encryptionManager() {
      return encryptionManager;
    }

    public boolean isCaseSensitive() {
      return caseSensitive;
    }

    private Schema tableSchema() {
      if (tableSchema == null) {
        this.tableSchema = SchemaParser.fromJson(tableSchemaString);
      }
      return tableSchema;
    }

    private Schema expectedSchema() {
      if (expectedSchema == null) {
        this.expectedSchema = SchemaParser.fromJson(expectedSchemaString);
      }
      return expectedSchema;
    }

    private String[] metadataColumns() {
      return metaColumns;
    }
  }

  private static class TaskDataReader implements PartitionReader<InternalRow> {
    // for some reason, the apply method can't be called from Java without reflection
    private static final DynMethods.UnboundMethod APPLY_PROJECTION = DynMethods.builder("apply")
        .impl(UnsafeProjection.class, InternalRow.class)
        .build();

    private final ReadTask split;
    private final Iterator<FileScanTask> tasks;
    private final Map<String, InputFile> inputFiles;

    private Iterator<InternalRow> currentIterator = null;
    private Closeable currentCloseable = null;
    private InternalRow current = null;

    TaskDataReader(ReadTask task) {
      this.split = task;
      this.tasks = task.files().iterator();
      this.inputFiles = batchDecrypt(task.files());

      // open last because the split must be set
      this.currentIterator = open(tasks.next());
    }

    @Override
    public boolean next() throws IOException {
      while (true) {
        if (currentIterator.hasNext()) {
          this.current = currentIterator.next();
          return true;

        } else if (tasks.hasNext()) {
          this.currentCloseable.close();
          this.currentIterator = open(tasks.next());

        } else {
          return false;
        }
      }
    }

    @Override
    public InternalRow get() {
      return current;
    }

    @Override
    public void close() throws IOException {
      InputFileBlockHolder.unset();

      // close the current iterator
      this.currentCloseable.close();

      // exhaust the task iterator
      while (tasks.hasNext()) {
        tasks.next();
      }
    }

    private Map<String, InputFile> batchDecrypt(Collection<FileScanTask> files) {
      Iterable<InputFile> decryptedFiles = split.encryptionManager().decrypt(
          Iterables.transform(files,
              fileScanTask ->
                  EncryptedFiles.encryptedInput(
                      split.io().newInputFile(fileScanTask.file().path().toString()),
                      fileScanTask.file().keyMetadata())));

      ImmutableMap.Builder<String, InputFile> inputFileBuilder = ImmutableMap.builder();
      decryptedFiles.forEach(decrypted -> inputFileBuilder.put(decrypted.location(), decrypted));
      return inputFileBuilder.build();
    }

    private Iterator<InternalRow> open(FileScanTask task) {
      DataFile file = task.file();

      // update the current file for Spark's filename() function
      InputFileBlockHolder.set(file.path().toString(), task.start(), task.length());

      // metadata columns
      List<MetadataColumn> metaColumns = Stream.of(split.metadataColumns())
          .map(MetadataColumns::get)
          .collect(Collectors.toList());
      Types.StructType metaStruct = MetadataColumns.toStruct(metaColumns);

      // schema or rows returned by readers
      Schema finalSchema = TypeUtil.join(split.expectedSchema(), metaStruct);
      PartitionSpec spec = task.spec();
      Set<Integer> idColumns = spec.identitySourceIds();

      // schema needed for the projection and filtering
      StructType sparkType = SparkSchemaUtil.convert(finalSchema);
      Schema requiredSchema = SparkSchemaUtil.prune(
          split.tableSchema(), sparkType, task.residual(), split.isCaseSensitive());
      boolean hasJoinedPartitionColumns = !idColumns.isEmpty();
      boolean hasExtraFilterColumns = requiredSchema.columns().size() != finalSchema.columns().size();

      Schema iterSchema;
      Iterator<InternalRow> iter;

      if (hasJoinedPartitionColumns || !metaColumns.isEmpty()) {
        // schema used to read data files
        Schema readSchema = TypeUtil.selectNot(requiredSchema, idColumns);
        Schema partitionSchema = TypeUtil.select(requiredSchema, idColumns);
        PartitionRowConverter convertToRow = new PartitionRowConverter(partitionSchema, spec);
        InternalRow partition = convertToRow.apply(file.partition());

        JoinedRow joined = new JoinedRow();
        Schema joinedType;

        if (!metaColumns.isEmpty()) {
          // TODO: support metadata columns other than _file
          Preconditions.checkArgument(
              metaColumns.stream().allMatch(mc -> mc == MetadataColumns.FILE_PATH),
              "Unsupported metadata columns: only _file is supported");

          Row metaRow = Row.of(Stream.of(metaColumns).map(any -> file.path()).toArray());
          StructInternalRow meta = new StructInternalRow(metaStruct).setStruct(metaRow);

          joinedType = TypeUtil.join(partitionSchema, metaStruct);
          joined.withRight(new JoinedRow().withLeft(partition).withRight(meta));
        } else {
          joinedType = partitionSchema;
          joined.withRight(partition);
        }

        // create joined rows and project from the joined schema to the final schema
        iterSchema = TypeUtil.join(readSchema, joinedType);
        iter = Iterators.transform(open(task, readSchema), joined::withLeft);

      } else if (hasExtraFilterColumns) {
        // add projection to the final schema
        iterSchema = requiredSchema;
        iter = open(task, requiredSchema);

      } else {
        // return the base iterator
        iterSchema = finalSchema;
        iter = open(task, finalSchema);
      }

      // TODO: remove the projection by reporting the iterator's schema back to Spark
      return Iterators.transform(iter,
          APPLY_PROJECTION.bind(projection(finalSchema, iterSchema))::invoke);
    }

    private Iterator<InternalRow> open(FileScanTask task, Schema readSchema) {
      CloseableIterable<InternalRow> iter;
      if (task.isDataTask()) {
        iter = newDataIterable(task.asDataTask(), readSchema);

      } else {
        InputFile location = inputFiles.get(task.file().path().toString());
        Preconditions.checkNotNull(location, "Could not find InputFile associated with FileScanTask");

        switch (task.file().format()) {
          case PARQUET:
            iter = newParquetIterable(location, task, readSchema);
            break;

          case AVRO:
            iter = newAvroIterable(location, task, readSchema);
            break;

          case ORC:
            iter = newOrcIterable(location, task, readSchema);
            break;

          default:
            throw new UnsupportedOperationException(
                "Cannot read unknown format: " + task.file().format());
        }
      }

      this.currentCloseable = iter;

      return iter.iterator();
    }

    private static UnsafeProjection projection(Schema finalSchema, Schema readSchema) {
      StructType struct = SparkSchemaUtil.convert(readSchema);

      List<AttributeReference> refs = JavaConverters.seqAsJavaListConverter(struct.toAttributes()).asJava();
      List<Attribute> attrs = Lists.newArrayListWithExpectedSize(struct.fields().length);
      List<org.apache.spark.sql.catalyst.expressions.Expression> exprs =
          Lists.newArrayListWithExpectedSize(struct.fields().length);

      for (AttributeReference ref : refs) {
        attrs.add(ref.toAttribute());
      }

      for (Types.NestedField field : finalSchema.columns()) {
        int indexInReadSchema = struct.fieldIndex(field.name());
        exprs.add(refs.get(indexInReadSchema));
      }

      return UnsafeProjection.create(
          JavaConverters.asScalaBufferConverter(exprs).asScala().toSeq(),
          JavaConverters.asScalaBufferConverter(attrs).asScala().toSeq());
    }

    private CloseableIterable<InternalRow> newAvroIterable(InputFile location,
                                                      FileScanTask task,
                                                      Schema readSchema) {
      return Avro.read(location)
          .reuseContainers()
          .project(readSchema)
          .split(task.start(), task.length())
          .createReaderFunc(SparkAvroReader::new)
          .build();
    }

    private CloseableIterable<InternalRow> newParquetIterable(InputFile location,
                                                            FileScanTask task,
                                                            Schema readSchema) {
      return Parquet.read(location)
          .project(readSchema)
          .split(task.start(), task.length())
          .createReaderFunc(fileSchema -> SparkParquetReaders.buildReader(readSchema, fileSchema))
          .filter(task.residual())
          .caseSensitive(split.isCaseSensitive())
          .build();
    }

    private CloseableIterable<InternalRow> newOrcIterable(InputFile location,
                                                          FileScanTask task,
                                                          Schema readSchema) {
      return ORC.read(location)
          .schema(readSchema)
          .split(task.start(), task.length())
          .createReaderFunc(SparkOrcReader::new)
          .caseSensitive(split.isCaseSensitive())
          .build();
    }

    private CloseableIterable<InternalRow> newDataIterable(DataTask task, Schema readSchema) {
      StructInternalRow row = new StructInternalRow(split.tableSchema().asStruct());
      CloseableIterable<InternalRow> asSparkRows = CloseableIterable.transform(
          task.asDataTask().rows(), row::setStruct);
      return CloseableIterable.transform(
          asSparkRows, APPLY_PROJECTION.bind(projection(readSchema, split.tableSchema()))::invoke);
    }
  }

  private static class PartitionRowConverter implements Function<StructLike, InternalRow> {
    private final DataType[] types;
    private final int[] positions;
    private final Class<?>[] javaTypes;
    private final GenericInternalRow reusedRow;

    PartitionRowConverter(Schema partitionSchema, PartitionSpec spec) {
      StructType partitionType = SparkSchemaUtil.convert(partitionSchema);
      StructField[] fields = partitionType.fields();

      this.types = new DataType[fields.length];
      this.positions = new int[types.length];
      this.javaTypes = new Class<?>[types.length];
      this.reusedRow = new GenericInternalRow(types.length);

      List<PartitionField> partitionFields = spec.fields();
      for (int rowIndex = 0; rowIndex < fields.length; rowIndex += 1) {
        this.types[rowIndex] = fields[rowIndex].dataType();

        int sourceId = partitionSchema.columns().get(rowIndex).fieldId();
        for (int specIndex = 0; specIndex < partitionFields.size(); specIndex += 1) {
          PartitionField field = spec.fields().get(specIndex);
          if (field.sourceId() == sourceId && "identity".equals(field.transform().toString())) {
            positions[rowIndex] = specIndex;
            javaTypes[rowIndex] = spec.javaClasses()[specIndex];
            break;
          }
        }
      }
    }

    @Override
    public InternalRow apply(StructLike tuple) {
      for (int i = 0; i < types.length; i += 1) {
        Object value = tuple.get(positions[i], javaTypes[i]);
        if (value != null) {
          reusedRow.update(i, convert(value, types[i]));
        } else {
          reusedRow.setNullAt(i);
        }
      }

      return reusedRow;
    }

    /**
     * Converts the objects into instances used by Spark's InternalRow.
     *
     * @param value a data value
     * @param type  the Spark data type
     * @return the value converted to the representation expected by Spark's InternalRow.
     */
    private static Object convert(Object value, DataType type) {
      if (type instanceof StringType) {
        return UTF8String.fromString(value.toString());
      } else if (type instanceof BinaryType) {
        return ByteBuffers.toByteArray((ByteBuffer) value);
      } else if (type instanceof DecimalType) {
        return Decimal.fromDecimal(value);
      }
      return value;
    }
  }

  private static class CacheKey {
    static CacheKey from(TableScan scan, Long splitSize) {
      return new CacheKey(
          scan.table().toString() + ":" + System.identityHashCode(scan.table()),
          scan.snapshot() != null ? scan.snapshot().snapshotId() : null,
          splitSize,
          scan.filter().toString(),
          scan.isCaseSensitive());
    }

    private final String table;
    private final Long snapshotId;
    private final Long splitSize;
    private final String filter;
    private final boolean isCaseSensitive;

    private CacheKey(String table, Long snapshotId, Long splitSize, String filter, boolean isCaseSensitive) {
      this.table = table;
      this.snapshotId = snapshotId;
      this.splitSize = splitSize;
      this.filter = filter;
      this.isCaseSensitive = isCaseSensitive;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }

      if (obj == null || getClass() != obj.getClass()) {
        return false;
      }

      CacheKey that = (CacheKey) obj;
      return Objects.equals(snapshotId, that.snapshotId) &&
          Objects.equals(splitSize, that.splitSize) &&
          Objects.equals(table, that.table) &&
          Objects.equals(filter, that.filter) &&
          isCaseSensitive == that.isCaseSensitive;
    }

    @Override
    public int hashCode() {
      return Objects.hash(table, snapshotId, splitSize, filter, isCaseSensitive);
    }
  }

  /**
   * Implements {@link StructLike#get} for passing static rows.
   */
  static class Row implements StructLike, Serializable {
    public static Row of(Object... values) {
      return new Row(values);
    }

    private final Object[] values;

    private Row(Object... values) {
      this.values = values;
    }

    @Override
    public int size() {
      return values.length;
    }

    @Override
    public <T> T get(int pos, Class<T> javaClass) {
      return javaClass.cast(values[pos]);
    }

    @Override
    public <T> void set(int pos, T value) {
      throw new UnsupportedOperationException("Setting values is not supported");
    }
  }

  private static class StructLikeInternalRow implements StructLike {
    private final DataType[] types;
    private InternalRow row = null;

    StructLikeInternalRow(StructType struct) {
      this.types = new DataType[struct.size()];
      StructField[] fields = struct.fields();
      for (int i = 0; i < fields.length; i += 1) {
        types[i] = fields[i].dataType();
      }
    }

    public StructLikeInternalRow setRow(InternalRow row) {
      this.row = row;
      return this;
    }

    @Override
    public int size() {
      return types.length;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(int pos, Class<T> javaClass) {
      return javaClass.cast(row.get(pos, types[pos]));
    }

    @Override
    public <T> void set(int pos, T value) {
      throw new UnsupportedOperationException("Not implemented: set");
    }
  }
}
