/*
 * Copyright 2018 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.iceberg.pig;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.netflix.iceberg.CombinedScanTask;
import com.netflix.iceberg.DataFile;
import com.netflix.iceberg.FileScanTask;
import com.netflix.iceberg.PartitionSpec;
import com.netflix.iceberg.Schema;
import com.netflix.iceberg.Table;
import com.netflix.iceberg.TableScan;
import com.netflix.iceberg.expressions.Expression;
import com.netflix.iceberg.hadoop.HadoopInputFile;
import com.netflix.iceberg.io.CloseableIterable;
import com.netflix.iceberg.io.InputFile;
import com.netflix.iceberg.parquet.Parquet;
import com.netflix.iceberg.types.Type;
import com.netflix.iceberg.types.TypeUtil;
import com.netflix.iceberg.types.Types;
import org.apache.commons.lang.SerializationUtils;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.InputSplit;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.RecordReader;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.pig.data.DataByteArray;
import org.apache.pig.impl.util.ObjectSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.netflix.iceberg.pig.SchemaUtil.project;

public class IcebergPigInputFormat<T> extends InputFormat<Void, T> {
  private static final Logger LOG = LoggerFactory.getLogger(IcebergPigInputFormat.class);

  static final String ICEBERG_SCHEMA = "iceberg.schema";
  static final String ICEBERG_PROJECTED_FIELDS = "iceberg.projected.fields";
  static final String ICEBERG_FILTER_EXPRESSION = "iceberg.filter.expression";

  private Table table;
  private List<InputSplit> splits;

  IcebergPigInputFormat(Table table) {
    this.table = table;
  }

  @Override
  @SuppressWarnings("unchecked")
  public List<InputSplit> getSplits(JobContext context) throws IOException {
    if (splits != null) {
      LOG.info("Returning cached splits: " + splits.size());
      return splits;
    }

    splits = Lists.newArrayList();

    TableScan scan = table.newScan();

    //Apply Filters
    Expression filterExpression = (Expression) ObjectSerializer.deserialize(context.getConfiguration().get(ICEBERG_FILTER_EXPRESSION));

    if (filterExpression != null) {
      LOG.info("Filter Expression: " + filterExpression);
      scan = scan.filter(filterExpression);
    }

    //Wrap in Splits
    try (CloseableIterable<CombinedScanTask> tasks = scan.planTasks()) {
      tasks.forEach((scanTask) -> splits.add(new IcebergSplit(scanTask)));
    }

    return splits;
  }

  @Override
  public RecordReader<Void, T> createRecordReader(InputSplit split, TaskAttemptContext context) {
    return new IcebergRecordReader<>();
  }

  private static class IcebergSplit extends InputSplit implements Writable {
    private CombinedScanTask task;

    IcebergSplit(CombinedScanTask task) {
      this.task = task;
    }

    public IcebergSplit() {

    }

    @Override
    public long getLength() {
      return task.files().stream().mapToLong(FileScanTask::length).sum();
    }

    @Override
    public String[] getLocations() {
      return new String[0];
    }

    @Override
    public void write(DataOutput out) throws IOException {
      byte[] data = SerializationUtils.serialize(this.task);
      out.writeInt(data.length);
      out.write(data);
    }

    @Override
    public void readFields(DataInput in) throws IOException {
      byte[] data = new byte[in.readInt()];
      in.readFully(data);

      this.task = (CombinedScanTask) SerializationUtils.deserialize(data);
    }
  }

  public class IcebergRecordReader<T> extends RecordReader<Void, T> {
    private TaskAttemptContext context;

    private Iterator<FileScanTask> tasks;
    private FileScanTask currentTask;

    private CloseableIterable reader;
    private Iterator<T> recordIterator;
    private T currentRecord;

    @Override
    public void initialize(InputSplit split, TaskAttemptContext context) throws IOException {
      this.context = context;

      CombinedScanTask task = ((IcebergSplit) split).task;
      tasks = task.files().iterator();

      advance();
    }

    @SuppressWarnings("unchecked")
    private boolean advance() throws IOException {
      if(reader != null) {
        reader.close();
      }

      if (!tasks.hasNext()) {
        return false;
      }

      currentTask = tasks.next();

      Schema tableSchema = (Schema) ObjectSerializer.deserialize(context.getConfiguration().get(ICEBERG_SCHEMA));
      List<String> projectedFields = (List<String>) ObjectSerializer.deserialize(context.getConfiguration().get(ICEBERG_PROJECTED_FIELDS));

      Schema projectedSchema = projectedFields != null ? project(tableSchema, projectedFields) : tableSchema;

      PartitionSpec spec = currentTask.asFileScanTask().spec();
      DataFile file = currentTask.file();
      InputFile inputFile = HadoopInputFile.fromLocation(file.path(), context.getConfiguration());

      Set<Integer> idColumns = spec.identitySourceIds();

      // schema needed for the projection and filtering
      boolean hasJoinedPartitionColumns = !idColumns.isEmpty();

      switch (file.format()) {
        case PARQUET:
          Map<Integer, Object> partitionValueMap = Maps.newHashMap();

          if (hasJoinedPartitionColumns) {

            Schema readSchema = TypeUtil.selectNot(projectedSchema, idColumns);
            Schema partitionSchema = TypeUtil.select(tableSchema, idColumns);
            Schema projectedPartitionSchema = TypeUtil.select(projectedSchema, idColumns);

            for (Types.NestedField field : projectedPartitionSchema.columns()) {
              int tupleIndex = projectedSchema.columns().indexOf(field);
              int partitionIndex = partitionSchema.columns().indexOf(field);

              Object partitionValue = file.partition().get(partitionIndex, Object.class);
              partitionValueMap.put(tupleIndex, convertPartitionValue(field.type(), partitionValue));
            }

            reader = Parquet.read(inputFile)
                .project(readSchema)
                .split(currentTask.start(), currentTask.length())
                .filter(currentTask.residual())
                .createReaderFunc(fileSchema -> PigParquetReader.buildReader(fileSchema, readSchema, partitionValueMap))
                .build();
          } else {
            reader = Parquet.read(inputFile)
                .project(projectedSchema)
                .split(currentTask.start(), currentTask.length())
                .filter(currentTask.residual())
                .createReaderFunc(fileSchema -> PigParquetReader.buildReader(fileSchema, projectedSchema, partitionValueMap))
                .build();
          }

          recordIterator = reader.iterator();

          break;
        default:
          throw new UnsupportedOperationException("Unsupported file format: " + file.format());
      }

      return true;
    }

    private Object convertPartitionValue(Type type, Object value) {
      if(type.typeId() == Types.BinaryType.get().typeId()) {
        ByteBuffer buffer = (ByteBuffer) value;
        return new DataByteArray(buffer.get(new byte[buffer.remaining()]).array());
      }

      return value;
    }

    @Override
    public boolean nextKeyValue() throws IOException {
      if (recordIterator.hasNext() || advance()) {
        currentRecord = recordIterator.next();
        return true;
      }
      
      return false;
    }

    @Override
    public Void getCurrentKey() {
      return null;
    }

    @Override
    public T getCurrentValue() {
      return currentRecord;
    }

    @Override
    public float getProgress() {
      return 0;
    }

    @Override
    public void close() {

    }


  }
}
