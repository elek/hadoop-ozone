/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.hadoop.hdds.scm.storage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hadoop.hdds.client.BlockID;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.protocol.MockDatanodeDetails;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.ChecksumType;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.ContainerCommandRequestProto;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.ContainerCommandResponseProto;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.GetCommittedBlockLengthResponseProto;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.PutBlockResponseProto;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.Result;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.Type;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.ReplicationFactor;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.ReplicationType;
import org.apache.hadoop.hdds.scm.XceiverClientManager;
import org.apache.hadoop.hdds.scm.XceiverClientReply;
import org.apache.hadoop.hdds.scm.XceiverClientSpi;
import org.apache.hadoop.hdds.scm.pipeline.Pipeline;
import org.apache.hadoop.hdds.scm.pipeline.Pipeline.Builder;
import org.apache.hadoop.hdds.scm.pipeline.Pipeline.PipelineState;
import org.apache.hadoop.hdds.scm.pipeline.PipelineID;

import org.apache.ratis.thirdparty.com.google.protobuf.ByteString;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * UNIT test for BlockOutputStream.
 * <p>
 * Compares bytes written to the stream and received in the ChunkWriteRequests.
 */
public class TestBlockOutputStreamCorrectness {

  private static final long SEED = 18480315L;

  private int writeUnitSize = 10;

  @Test
  public void test() throws IOException {
    List<DatanodeDetails> nodes = new ArrayList<>();
    nodes.add(MockDatanodeDetails.randomDatanodeDetails());
    nodes.add(MockDatanodeDetails.randomDatanodeDetails());
    nodes.add(MockDatanodeDetails.randomDatanodeDetails());

    final Pipeline pipeline = new Builder()
        .setFactor(ReplicationFactor.THREE)
        .setType(ReplicationType.RATIS)
        .setState(PipelineState.OPEN)
        .setId(PipelineID.randomId())
        .setNodes(nodes)
        .build();

    final XceiverClientManager xcm = Mockito.mock(XceiverClientManager.class);
    Mockito.when(xcm.acquireClient(Mockito.any()))
        .thenReturn(new MockXceiverClientSpi(pipeline));

    BlockOutputStream outputStream = new BlockOutputStream(
        new BlockID(1L, 1L),
        xcm,
        pipeline,
        4 * 1024 * 1024,
        16 * 1024 * 1024,
        true,
        32 * 1024 * 1024,
        new BufferPool(4 * 1024 * 1024, 32 / 4),
        ChecksumType.NONE,
        256 * 1024);

    Random random = new Random(SEED);

    int max = 50 * 1024 * 1024 / writeUnitSize;

    byte[] writeBuffer = new byte[writeUnitSize];
    for (int t = 0; t < max; t++) {
      if (writeUnitSize > 1) {
        for (int i = 0; i < writeBuffer.length; i++) {
          writeBuffer[i] = (byte) random.nextInt();
        }
        outputStream.write(writeBuffer, 0, writeBuffer.length);
      } else {
        outputStream.write((byte) random.nextInt());
      }
    }
    outputStream.close();
  }

  private class MockXceiverClientSpi extends XceiverClientSpi {

    private final Pipeline pipeline;

    private Random expectedRandomStream = new Random(SEED);

    private AtomicInteger counter = new AtomicInteger();

    public MockXceiverClientSpi(Pipeline pipeline) {
      super();
      this.pipeline = pipeline;
    }

    @Override
    public void connect() throws Exception {

    }

    @Override
    public void connect(String encodedToken) throws Exception {

    }

    @Override
    public void close() {

    }

    @Override
    public Pipeline getPipeline() {
      return pipeline;
    }

    @Override
    public XceiverClientReply sendCommandAsync(ContainerCommandRequestProto request)
        throws IOException, ExecutionException, InterruptedException {

      final ContainerCommandResponseProto.Builder builder =
          ContainerCommandResponseProto.newBuilder()
              .setResult(Result.SUCCESS)
              .setCmdType(request.getCmdType());

      switch (request.getCmdType()) {
      case PutBlock:
        builder.setPutBlock(PutBlockResponseProto.newBuilder()
            .setCommittedBlockLength(
                GetCommittedBlockLengthResponseProto.newBuilder()
                    .setBlockID(
                        request.getPutBlock().getBlockData().getBlockID())
                    .setBlockLength(
                        request.getPutBlock().getBlockData().getSize())
                    .build())
            .build());
      case WriteChunk:
        ByteString data = request.getWriteChunk().getData();
        final byte[] writePayload = data.toByteArray();
        for (int i = 0; i < writePayload.length; i++) {
          byte expectedByte = (byte) expectedRandomStream.nextInt();
          Assert.assertEquals(expectedByte,
              writePayload[i]);
        }
      }
      final XceiverClientReply result = new XceiverClientReply(
          CompletableFuture.completedFuture(builder.build()));
      result.setLogIndex(counter.incrementAndGet());
      return result;

    }

    @Override
    public ReplicationType getPipelineType() {
      return null;
    }

    @Override
    public XceiverClientReply watchForCommit(long index)
        throws InterruptedException, ExecutionException, TimeoutException,
        IOException {
      final ContainerCommandResponseProto.Builder builder =
          ContainerCommandResponseProto.newBuilder()
              .setCmdType(Type.WriteChunk)
              .setResult(Result.SUCCESS);
      final XceiverClientReply xceiverClientReply = new XceiverClientReply(
          CompletableFuture.completedFuture(builder.build()));
      xceiverClientReply.setLogIndex(index);
      return xceiverClientReply;
    }

    @Override
    public long getReplicatedMinCommitIndex() {
      return 0;
    }
  }

}