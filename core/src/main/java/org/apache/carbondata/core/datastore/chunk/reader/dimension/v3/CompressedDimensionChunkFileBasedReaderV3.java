/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.carbondata.core.datastore.chunk.reader.dimension.v3;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;

import org.apache.carbondata.core.datastore.FileHolder;
import org.apache.carbondata.core.datastore.chunk.DimensionColumnDataChunk;
import org.apache.carbondata.core.datastore.chunk.impl.DimensionRawColumnChunk;
import org.apache.carbondata.core.datastore.chunk.impl.FixedLengthDimensionDataChunk;
import org.apache.carbondata.core.datastore.chunk.impl.VariableLengthDimensionDataChunk;
import org.apache.carbondata.core.datastore.chunk.reader.dimension.AbstractChunkReaderV2V3Format;
import org.apache.carbondata.core.datastore.chunk.store.ColumnPageWrapper;
import org.apache.carbondata.core.datastore.columnar.UnBlockIndexer;
import org.apache.carbondata.core.datastore.page.ColumnPage;
import org.apache.carbondata.core.datastore.page.encoding.ColumnPageDecoder;
import org.apache.carbondata.core.datastore.page.encoding.DefaultEncodingFactory;
import org.apache.carbondata.core.datastore.page.encoding.EncodingFactory;
import org.apache.carbondata.core.memory.MemoryException;
import org.apache.carbondata.core.metadata.blocklet.BlockletInfo;
import org.apache.carbondata.core.util.CarbonUtil;
import org.apache.carbondata.format.DataChunk2;
import org.apache.carbondata.format.DataChunk3;
import org.apache.carbondata.format.Encoding;

import org.apache.commons.lang.ArrayUtils;

/**
 * Dimension column V3 Reader class which will be used to read and uncompress
 * V3 format data
 * data format
 * Data Format
 * <FileHeader>
 * <Column1 Data ChunkV3><Column1<Page1><Page2><Page3><Page4>>
 * <Column2 Data ChunkV3><Column2<Page1><Page2><Page3><Page4>>
 * <Column3 Data ChunkV3><Column3<Page1><Page2><Page3><Page4>>
 * <Column4 Data ChunkV3><Column4<Page1><Page2><Page3><Page4>>
 * <File Footer>
 */
public class CompressedDimensionChunkFileBasedReaderV3 extends AbstractChunkReaderV2V3Format {

  private EncodingFactory encodingFactory = DefaultEncodingFactory.getInstance();

  /**
   * end position of last dimension in carbon data file
   */
  private long lastDimensionOffsets;

  public CompressedDimensionChunkFileBasedReaderV3(BlockletInfo blockletInfo,
      int[] eachColumnValueSize, String filePath) {
    super(blockletInfo, eachColumnValueSize, filePath);
    lastDimensionOffsets = blockletInfo.getDimensionOffset();
  }

  /**
   * Below method will be used to read the dimension column data form carbon data file
   * Steps for reading
   * 1. Get the length of the data to be read
   * 2. Allocate the direct buffer
   * 3. read the data from file
   * 4. Get the data chunk object from data read
   * 5. Create the raw chunk object and fill the details
   *
   * @param fileReader          reader for reading the column from carbon data file
   * @param blockletColumnIndex blocklet index of the column in carbon data file
   * @return dimension raw chunk
   */
  public DimensionRawColumnChunk readRawDimensionChunk(FileHolder fileReader,
      int blockletColumnIndex) throws IOException {
    // get the current dimension offset
    long currentDimensionOffset = dimensionChunksOffset.get(blockletColumnIndex);
    int length = 0;
    // to calculate the length of the data to be read
    // column other than last column we can subtract the offset of current column with
    // next column and get the total length.
    // but for last column we need to use lastDimensionOffset which is the end position
    // of the last dimension, we can subtract current dimension offset from lastDimesionOffset
    if (dimensionChunksOffset.size() - 1 == blockletColumnIndex) {
      length = (int) (lastDimensionOffsets - currentDimensionOffset);
    } else {
      length = (int) (dimensionChunksOffset.get(blockletColumnIndex + 1) - currentDimensionOffset);
    }
    ByteBuffer buffer = null;
    // read the data from carbon data file
    synchronized (fileReader) {
      buffer = fileReader.readByteBuffer(filePath, currentDimensionOffset, length);
    }
    // get the data chunk which will have all the details about the data pages
    DataChunk3 dataChunk = CarbonUtil.readDataChunk3(buffer, 0, length);
    // creating a raw chunks instance and filling all the details
    DimensionRawColumnChunk rawColumnChunk =
        new DimensionRawColumnChunk(blockletColumnIndex, buffer, 0, length, this);
    int numberOfPages = dataChunk.getPage_length().size();
    byte[][] maxValueOfEachPage = new byte[numberOfPages][];
    byte[][] minValueOfEachPage = new byte[numberOfPages][];
    int[] eachPageLength = new int[numberOfPages];
    for (int i = 0; i < minValueOfEachPage.length; i++) {
      maxValueOfEachPage[i] =
          dataChunk.getData_chunk_list().get(i).getMin_max().getMax_values().get(0).array();
      minValueOfEachPage[i] =
          dataChunk.getData_chunk_list().get(i).getMin_max().getMin_values().get(0).array();
      eachPageLength[i] = dataChunk.getData_chunk_list().get(i).getNumberOfRowsInpage();
    }
    rawColumnChunk.setDataChunkV3(dataChunk);
    rawColumnChunk.setFileHolder(fileReader);
    rawColumnChunk.setPagesCount(dataChunk.getPage_length().size());
    rawColumnChunk.setMaxValues(maxValueOfEachPage);
    rawColumnChunk.setMinValues(minValueOfEachPage);
    rawColumnChunk.setRowCount(eachPageLength);
    rawColumnChunk.setOffsets(ArrayUtils
        .toPrimitive(dataChunk.page_offset.toArray(new Integer[dataChunk.page_offset.size()])));
    return rawColumnChunk;
  }

  /**
   * Below method will be used to read the multiple dimension column data in group
   * and divide into dimension raw chunk object
   * Steps for reading
   * 1. Get the length of the data to be read
   * 2. Allocate the direct buffer
   * 3. read the data from file
   * 4. Get the data chunk object from file for each column
   * 5. Create the raw chunk object and fill the details for each column
   * 6. increment the offset of the data
   *
   * @param fileReader
   *        reader which will be used to read the dimension columns data from file
   * @param startBlockletColumnIndex
   *        blocklet index of the first dimension column
   * @param endBlockletColumnIndex
   *        blocklet index of the last dimension column
   * @ DimensionRawColumnChunk array
   */
  protected DimensionRawColumnChunk[] readRawDimensionChunksInGroup(FileHolder fileReader,
      int startBlockletColumnIndex, int endBlockletColumnIndex) throws IOException {
    // to calculate the length of the data to be read
    // column we can subtract the offset of start column offset with
    // end column+1 offset and get the total length.
    long currentDimensionOffset = dimensionChunksOffset.get(startBlockletColumnIndex);
    ByteBuffer buffer = null;
    // read the data from carbon data file
    synchronized (fileReader) {
      buffer = fileReader.readByteBuffer(filePath, currentDimensionOffset,
          (int) (dimensionChunksOffset.get(endBlockletColumnIndex + 1) - currentDimensionOffset));
    }
    // create raw chunk for each dimension column
    DimensionRawColumnChunk[] dimensionDataChunks =
        new DimensionRawColumnChunk[endBlockletColumnIndex - startBlockletColumnIndex + 1];
    int index = 0;
    int runningLength = 0;
    for (int i = startBlockletColumnIndex; i <= endBlockletColumnIndex; i++) {
      int currentLength = (int) (dimensionChunksOffset.get(i + 1) - dimensionChunksOffset.get(i));
      dimensionDataChunks[index] =
          new DimensionRawColumnChunk(i, buffer, runningLength, currentLength, this);
      DataChunk3 dataChunk =
          CarbonUtil.readDataChunk3(buffer, runningLength, dimensionChunksLength.get(i));
      int numberOfPages = dataChunk.getPage_length().size();
      byte[][] maxValueOfEachPage = new byte[numberOfPages][];
      byte[][] minValueOfEachPage = new byte[numberOfPages][];
      int[] eachPageLength = new int[numberOfPages];
      for (int j = 0; j < minValueOfEachPage.length; j++) {
        maxValueOfEachPage[j] =
            dataChunk.getData_chunk_list().get(j).getMin_max().getMax_values().get(0).array();
        minValueOfEachPage[j] =
            dataChunk.getData_chunk_list().get(j).getMin_max().getMin_values().get(0).array();
        eachPageLength[j] = dataChunk.getData_chunk_list().get(j).getNumberOfRowsInpage();
      }
      dimensionDataChunks[index].setDataChunkV3(dataChunk);
      dimensionDataChunks[index].setFileHolder(fileReader);
      dimensionDataChunks[index].setPagesCount(dataChunk.getPage_length().size());
      dimensionDataChunks[index].setMaxValues(maxValueOfEachPage);
      dimensionDataChunks[index].setMinValues(minValueOfEachPage);
      dimensionDataChunks[index].setRowCount(eachPageLength);
      dimensionDataChunks[index].setOffsets(ArrayUtils
          .toPrimitive(dataChunk.page_offset.toArray(new Integer[dataChunk.page_offset.size()])));
      runningLength += currentLength;
      index++;
    }
    return dimensionDataChunks;
  }

  /**
   * Below method will be used to convert the compressed dimension chunk raw data to actual data
   *
   * @param rawColumnPage dimension raw chunk
   * @param pageNumber              number
   * @return DimensionColumnDataChunk
   */
  @Override public DimensionColumnDataChunk convertToDimensionChunk(
      DimensionRawColumnChunk rawColumnPage, int pageNumber) throws IOException, MemoryException {
    // data chunk of blocklet column
    DataChunk3 dataChunk3 = rawColumnPage.getDataChunkV3();
    // get the data buffer
    ByteBuffer rawData = rawColumnPage.getRawData();
    DataChunk2 pageMetadata = dataChunk3.getData_chunk_list().get(pageNumber);
    // calculating the start point of data
    // as buffer can contain multiple column data, start point will be datachunkoffset +
    // data chunk length + page offset
    int offset = rawColumnPage.getOffSet() + dimensionChunksLength
        .get(rawColumnPage.getColumnIndex()) + dataChunk3.getPage_offset().get(pageNumber);
    // first read the data and uncompressed it
    return decodeDimension(rawColumnPage, rawData, pageMetadata, offset);
  }

  private ColumnPage decodeDimensionByMeta(DataChunk2 pageMetadata,
      ByteBuffer pageData, int offset)
      throws IOException, MemoryException {
    List<Encoding> encodings = pageMetadata.getEncoders();
    List<ByteBuffer> encoderMetas = pageMetadata.getEncoder_meta();
    ColumnPageDecoder decoder = encodingFactory.createDecoder(encodings, encoderMetas);
    return decoder.decode(pageData.array(), offset, pageMetadata.data_page_length);
  }

  private boolean isEncodedWithMeta(DataChunk2 pageMetadata) {
    List<Encoding> encodings = pageMetadata.getEncoders();
    if (encodings != null && encodings.size() == 1) {
      Encoding encoding = encodings.get(0);
      switch (encoding) {
        case DIRECT_COMPRESS:
        case DIRECT_STRING:
          return true;
      }
    }
    return false;
  }

  private DimensionColumnDataChunk decodeDimension(DimensionRawColumnChunk rawColumnPage,
      ByteBuffer pageData, DataChunk2 pageMetadata, int offset)
      throws IOException, MemoryException {
    if (isEncodedWithMeta(pageMetadata)) {
      ColumnPage decodedPage = decodeDimensionByMeta(pageMetadata, pageData, offset);
      return new ColumnPageWrapper(decodedPage,
          eachColumnValueSize[rawColumnPage.getColumnIndex()]);
    } else {
      // following code is for backward compatibility
      return decodeDimensionLegacy(rawColumnPage, pageData, pageMetadata, offset);
    }
  }

  private DimensionColumnDataChunk decodeDimensionLegacy(DimensionRawColumnChunk rawColumnPage,
      ByteBuffer pageData, DataChunk2 pageMetadata, int offset) {
    byte[] dataPage;
    int[] rlePage;
    int[] invertedIndexes = null;
    int[] invertedIndexesReverse = null;
    dataPage = COMPRESSOR.unCompressByte(pageData.array(), offset, pageMetadata.data_page_length);
    offset += pageMetadata.data_page_length;
    // if row id block is present then read the row id chunk and uncompress it
    if (hasEncoding(pageMetadata.encoders, Encoding.INVERTED_INDEX)) {
      invertedIndexes = CarbonUtil
          .getUnCompressColumnIndex(pageMetadata.rowid_page_length, pageData, offset);
      offset += pageMetadata.rowid_page_length;
      // get the reverse index
      invertedIndexesReverse = getInvertedReverseIndex(invertedIndexes);
    }
    // if rle is applied then read the rle block chunk and then uncompress
    //then actual data based on rle block
    if (hasEncoding(pageMetadata.encoders, Encoding.RLE)) {
      rlePage =
          CarbonUtil.getIntArray(pageData, offset, pageMetadata.rle_page_length);
      // uncompress the data with rle indexes
      dataPage = UnBlockIndexer.uncompressData(dataPage, rlePage,
          eachColumnValueSize[rawColumnPage.getColumnIndex()]);
    }

    DimensionColumnDataChunk columnDataChunk = null;

    // if no dictionary column then first create a no dictionary column chunk
    // and set to data chunk instance
    if (!hasEncoding(pageMetadata.encoders, Encoding.DICTIONARY)) {
      columnDataChunk =
          new VariableLengthDimensionDataChunk(dataPage, invertedIndexes, invertedIndexesReverse,
              pageMetadata.getNumberOfRowsInpage());
    } else {
      // to store fixed length column chunk values
      columnDataChunk =
          new FixedLengthDimensionDataChunk(dataPage, invertedIndexes, invertedIndexesReverse,
              pageMetadata.getNumberOfRowsInpage(),
              eachColumnValueSize[rawColumnPage.getColumnIndex()]);
    }
    return columnDataChunk;
  }
}
