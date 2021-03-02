package org.apache.hadoop.ozone.container.replication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.protocol.DatanodeDetails.Port.Name;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.ChecksumData;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.ChecksumType;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.ChunkInfo;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.ContainerCommandRequestProto;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.ContainerDataProto.State;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.ContainerType;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.DatanodeBlockID;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.Type;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.WriteChunkRequestProto;
import org.apache.hadoop.hdds.security.x509.SecurityConfig;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.ozone.container.common.helpers.ContainerMetrics;
import org.apache.hadoop.ozone.container.common.impl.ContainerSet;
import org.apache.hadoop.ozone.container.common.interfaces.Handler;
import org.apache.hadoop.ozone.container.common.interfaces.VolumeChoosingPolicy;
import org.apache.hadoop.ozone.container.common.transport.server.ratis.DispatcherContext;
import org.apache.hadoop.ozone.container.common.volume.HddsVolume;
import org.apache.hadoop.ozone.container.common.volume.MutableVolumeSet;
import org.apache.hadoop.ozone.container.common.volume.RoundRobinVolumeChoosingPolicy;
import org.apache.hadoop.ozone.container.keyvalue.KeyValueContainer;
import org.apache.hadoop.ozone.container.keyvalue.KeyValueContainerData;
import org.apache.hadoop.ozone.container.keyvalue.KeyValueHandler;
import org.apache.hadoop.ozone.container.ozoneimpl.ContainerController;
import org.apache.hadoop.ozone.container.replication.ReplicationServer.ReplicationConfig;
import org.apache.hadoop.test.GenericTestUtils;

import com.google.common.base.Supplier;
import com.google.common.collect.Maps;
import org.apache.commons.io.FileUtils;
import org.apache.ratis.thirdparty.com.google.protobuf.ByteString;
import org.jetbrains.annotations.NotNull;
import org.junit.Assert;
import org.junit.Test;

/**
 * Testing end2end replication without datanode.
 */
public class TestReplicationService {

  @Test
  public void test() throws Exception {

    final String scmUuid = "99335668-b0b2-46ff-bd6e-7bc9cc0e1404";
    final String clusterUuid = "    7001371d-d474-4ae2-bd80-d942b31f8bc9";
    //start server
    final Path sourceDir = Paths
        .get(System.getProperty("user.dir"), "target", "test-data", "source");
    final Path destDir = Paths
        .get(System.getProperty("user.dir"), "target", "test-data", "dest");
    FileUtils.deleteDirectory(sourceDir.toFile());
    FileUtils.deleteDirectory(destDir.toFile());

    final String sourceDnUUID = "d6979383-5fd5-4fa5-be02-9b39f06d763d";
    final String destDnUUID = "bb11a0cc-8902-4f07-adae-a853ba891132";

    ReplicationServer replicationServer =
        initSource(clusterUuid, scmUuid, destDnUUID, sourceDir.toString());

    //start client
    ContainerSet
        destinationContainerSet =
        replicateContainer(scmUuid,
            sourceDnUUID,
            replicationServer.getPort(),
            destDnUUID,
            destDir);

    GenericTestUtils.waitFor(new Supplier<Boolean>() {
      @Override
      public Boolean get() {
        return destinationContainerSet.getContainer(2L) != null;
      }
    }, 1000, 10_000);

    final Path firstBlockFile = destDir.resolve(
        "hdds/" + scmUuid + "/current/containerDir0/" + 2L + "/chunks/1.block");
    Assert.assertTrue("Block files is missing " + firstBlockFile,
        Files.exists(firstBlockFile));
  }

  @NotNull
  private ContainerSet replicateContainer(
      String scmUuid,
      String sourceDnUUID,
      int port,
      String destDnUUID,
      Path destDir
      ) throws IOException {
    ContainerSet destinationContainerSet = new ContainerSet();

    OzoneConfiguration clientConfig = new OzoneConfiguration();
    clientConfig.set("hdds.datanode.dir", destDir.toString());
    MutableVolumeSet volumeSet =
        new MutableVolumeSet(destDnUUID, clientConfig);

    DownloadAndImportReplicator replicator = new DownloadAndImportReplicator(
        clientConfig,
        () -> scmUuid,
        destinationContainerSet,
        volumeSet);

    DatanodeDetails source =
        DatanodeDetails.newBuilder()
            .setIpAddress("127.0.0.1")
            .setUuid(UUID.fromString(sourceDnUUID))
            .build();

    source.setPort(Name.REPLICATION, port);
    List<DatanodeDetails> sourceDatanodes = new ArrayList<>();
    sourceDatanodes.add(source);

    ReplicationSupervisor supervisor =
        new ReplicationSupervisor(destinationContainerSet, replicator, 10);
    replicator.replicate(new ReplicationTask(2L, sourceDatanodes));
    return destinationContainerSet;
  }

  private ReplicationServer initSource(
      String clusterUuid,
      String scmUuid,
      String sourceDnUUID,
      String sourceDir
  )
      throws Exception {
    OzoneConfiguration ozoneConfig = new OzoneConfiguration();
    ozoneConfig.set("hdds.datanode.dir", sourceDir);

    MutableVolumeSet sourceVolumes =
        new MutableVolumeSet(sourceDnUUID, ozoneConfig);

    VolumeChoosingPolicy v = new RoundRobinVolumeChoosingPolicy();
    final HddsVolume volume =
        v.chooseVolume(sourceVolumes.getVolumesList(), 5L);
    volume.format(clusterUuid);

    KeyValueContainerData kvd = new KeyValueContainerData(2L, "/tmp/asd");
    kvd.setState(State.OPEN);
    kvd.assignToVolume(scmUuid.toString(), volume);
    kvd.setSchemaVersion(OzoneConsts.SCHEMA_V2);
    KeyValueContainer kvc = new KeyValueContainer(kvd, ozoneConfig);
    kvc.create(sourceVolumes, v, scmUuid.toString());

    ContainerSet sourceContainerSet = new ContainerSet();
    sourceContainerSet.addContainer(kvc);

    KeyValueHandler handler = new KeyValueHandler(ozoneConfig,
        sourceDnUUID, sourceContainerSet, sourceVolumes,
        new ContainerMetrics(new int[] {}),
        containerReplicaProto -> {
        });

    final ContainerCommandRequestProto containerCommandRequest =
        ContainerCommandRequestProto.newBuilder()
            .setCmdType(Type.WriteChunk)
            .setDatanodeUuid(sourceDnUUID)
            .setContainerID(kvc.getContainerData().getContainerID())
            .setWriteChunk(WriteChunkRequestProto.newBuilder()
                .setBlockID(DatanodeBlockID.newBuilder()
                    .setContainerID(kvc.getContainerData().getContainerID())
                    .setBlockCommitSequenceId(1L)
                    .setLocalID(1L)
                    .build())
                .setData(ByteString.copyFromUtf8("asdf"))
                .setChunkData(ChunkInfo.newBuilder()
                    .setChunkName("chunk1")
                    .setOffset(1L)
                    .setLen(4)
                    .setChecksumData(ChecksumData.newBuilder()
                        .setType(ChecksumType.NONE)
                        .setBytesPerChecksum(16)
                        .build())
                    .build())
                .build())
            .build();

    handler.handle(containerCommandRequest, kvc,
        new DispatcherContext.Builder().build());

    HashMap<ContainerType, Handler> handlers = Maps.newHashMap();
    ContainerController controller =
        new ContainerController(sourceContainerSet, handlers);

    ReplicationConfig replicationConfig = new ReplicationConfig();
    replicationConfig.setPort(0);

    SecurityConfig securityConfig = new SecurityConfig(ozoneConfig);
    ReplicationServer replicationServer =
        new ReplicationServer(sourceContainerSet, replicationConfig, securityConfig,
            null);

    kvd.setState(State.CLOSED);

    replicationServer.init();
    replicationServer.start();
    return replicationServer;
  }

}