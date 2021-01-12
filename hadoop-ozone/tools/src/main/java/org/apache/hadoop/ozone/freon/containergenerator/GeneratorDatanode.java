package org.apache.hadoop.ozone.freon.containergenerator;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Properties;
import java.util.SplittableRandom;
import java.util.concurrent.Callable;

import org.apache.hadoop.hdds.cli.HddsVersionProvider;
import org.apache.hadoop.hdds.client.BlockID;
import org.apache.hadoop.hdds.conf.ConfigurationSource;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos;
import org.apache.hadoop.hdds.protocol.datanode.proto.ContainerProtos.ChecksumData;
import org.apache.hadoop.hdds.scm.OzoneClientConfig;
import org.apache.hadoop.hdds.scm.container.common.helpers.StorageContainerException;
import org.apache.hadoop.hdfs.server.datanode.StorageLocation;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.ozone.common.Checksum;
import org.apache.hadoop.ozone.common.InconsistentStorageStateException;
import org.apache.hadoop.ozone.container.common.helpers.BlockData;
import org.apache.hadoop.ozone.container.common.helpers.ChunkInfo;
import org.apache.hadoop.ozone.container.common.helpers.DatanodeVersionFile;
import org.apache.hadoop.ozone.container.common.impl.ChunkLayOutVersion;
import org.apache.hadoop.ozone.container.common.transport.server.ratis.DispatcherContext;
import org.apache.hadoop.ozone.container.common.transport.server.ratis.DispatcherContext.WriteChunkStage;
import org.apache.hadoop.ozone.container.common.utils.HddsVolumeUtil;
import org.apache.hadoop.ozone.container.common.volume.MutableVolumeSet;
import org.apache.hadoop.ozone.container.common.volume.RoundRobinVolumeChoosingPolicy;
import org.apache.hadoop.ozone.container.keyvalue.KeyValueContainer;
import org.apache.hadoop.ozone.container.keyvalue.KeyValueContainerData;
import org.apache.hadoop.ozone.container.keyvalue.impl.BlockManagerImpl;
import org.apache.hadoop.ozone.container.keyvalue.impl.ChunkManagerFactory;
import org.apache.hadoop.ozone.container.keyvalue.interfaces.BlockManager;
import org.apache.hadoop.ozone.container.keyvalue.interfaces.ChunkManager;
import org.apache.hadoop.ozone.freon.ContentGenerator;

import com.codahale.metrics.Timer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "crdn",
    description = "Offline container metadata generator for Ozone Datanodes",
    versionProvider = HddsVersionProvider.class,
    mixinStandardHelpOptions = true,
    showDefaultValues = true)
public class GeneratorDatanode extends BaseGenerator {

  @Option(names = {"--datanodes"},
      description = "Number of datanodes (to generate only subsequent of the "
          + "required containers).",
      defaultValue = "3")
  private int datanodes;

  @Option(names = {"--index"},
      description = "Index of the datanode. For example datanode #3 should "
          + "have only every 3rd container in a 10 node cluster.).",
      defaultValue = "1")
  private int datanodeIndex;

  @Option(names = {"--zero"},
      description = "User zero bytes instead of random data.",
      defaultValue = "false")
  private boolean zero;

  private ChunkManager chunkManager;

  private RoundRobinVolumeChoosingPolicy volumeChoosingPolicy;

  private MutableVolumeSet volumeSet;

  private Checksum checksum;

  private ConfigurationSource config;

  private Timer timer;

  private ContentGenerator contentGenerator;

  //Simulate ratis log index (incremented for each chunk write)
  private int logCounter;
  private String datanodeId;
  private String scmId;
  private int numberOfPipelines;
  private int currentPipeline;

  @Override
  public Void call() throws Exception {
    init();

    numberOfPipelines = datanodes / 3;

    //generate only containers for one datanodes
    setTestNo(getTestNo() / numberOfPipelines);

    currentPipeline = (datanodeIndex - 1) % numberOfPipelines;

    config = createOzoneConfiguration();

    BlockManager blockManager = new BlockManagerImpl(config);
    chunkManager = ChunkManagerFactory
        .createChunkManager(config, blockManager);

    final Collection<String> storageDirs =
        MutableVolumeSet.getDatanodeStorageDirs(config);

    String firstStorageDir =
        StorageLocation.parse(storageDirs.iterator().next())
            .getUri().getPath();

    scmId = Files.list(Paths.get(firstStorageDir, "hdds"))
        .filter(Files::isDirectory)
        .findFirst().get().getFileName().toString();

    final File versionFile = new File(firstStorageDir, "hdds/VERSION");
    Properties props = DatanodeVersionFile.readFrom(versionFile);
    if (props.isEmpty()) {
      throw new InconsistentStorageStateException(
          "Version file " + versionFile + " is missing");
    }

    String clusterId =
        HddsVolumeUtil.getProperty(props, OzoneConsts.CLUSTER_ID, versionFile);
    datanodeId = HddsVolumeUtil
        .getProperty(props, OzoneConsts.DATANODE_UUID, versionFile);

    volumeSet = new MutableVolumeSet(datanodeId, clusterId, config);

    volumeChoosingPolicy = new RoundRobinVolumeChoosingPolicy();

    final OzoneClientConfig ozoneClientConfig =
        config.getObject(OzoneClientConfig.class);
    checksum = new Checksum(ozoneClientConfig.getChecksumType(),
        ozoneClientConfig.getBytesPerChecksum());

    timer = getMetrics().timer("datanode-generator");
    runTests(this::generateData);
    return null;
  }

  private void generateData(long index) throws Exception {

    timer.time((Callable<Void>) () -> {

      long containerId =
          getContainerIdOffset() + index * numberOfPipelines + currentPipeline;

      SplittableRandom random = new SplittableRandom(containerId);

      int keyPerContainer = getKeysPerContainer();

      final KeyValueContainer container = createContainer(containerId);

      int chunkSize = 4096 * 1024;

      //loop to create multiple blocks per container
      for (long localId = 0; localId < keyPerContainer; localId++) {
        BlockID blockId = new BlockID(containerId, localId);
        BlockData blockData = new BlockData(blockId);

        int chunkIndex = 0;
        int writtenBytes = 0;

        //loop to create multiple chunks per blocks
        while (writtenBytes < getKeySize()) {
          int currentChunkSize =
              Math.min(getKeySize() - writtenBytes, chunkSize);
          String chunkName = "chunk" + chunkIndex++;

          final byte[] data = new byte[currentChunkSize];
          if (!zero) {
            generatedRandomData(random, data);
          }

          ByteBuffer byteBuffer = ByteBuffer.wrap(data);

          //it should be done BEFORE writeChunk consumes the buffer
          final ChecksumData checksumData =
              this.checksum.computeChecksum(byteBuffer).getProtoBufMessage();

          ChunkInfo chunkInfo =
              new ChunkInfo(chunkName, writtenBytes, currentChunkSize);
          writeChunk(container, blockId, chunkInfo, byteBuffer);

          //collect chunk info for putBlock
          blockData.addChunk(ContainerProtos.ChunkInfo.newBuilder()
              .setChunkName(chunkInfo.getChunkName())
              .setLen(chunkInfo.getLen())
              .setOffset(chunkInfo.getOffset())
              .setChecksumData(checksumData)
              .build());

          writtenBytes += currentChunkSize;
        }

        BlockManagerImpl.persistPutBlock(container, blockData, config, true);

      }

      container.close();

      return null;
    });

  }

  private void generatedRandomData(SplittableRandom random, byte[] data) {
    int bit = 0;
    int writtenBytes = 0;
    long currentNumber = 0;

    //this section generates one 4 bit long random number and reuse it 4 times
    while (writtenBytes < data.length) {
      if (bit == 0) {
        currentNumber = random.nextLong();
        bit = 3;
      } else {
        bit--;
      }
      data[writtenBytes++] = (byte) currentNumber;
      currentNumber = currentNumber >> 8;
    }
  }

  private KeyValueContainer createContainer(long containerId)
      throws IOException {
    ChunkLayOutVersion layoutVersion =
        ChunkLayOutVersion.getConfiguredVersion(config);
    KeyValueContainerData keyValueContainerData =
        new KeyValueContainerData(containerId, layoutVersion,
            getContainerSize(),
            getPrefix(), datanodeId);

    KeyValueContainer keyValueContainer =
        new KeyValueContainer(keyValueContainerData, config);

    try {
      keyValueContainer.create(volumeSet, volumeChoosingPolicy, scmId);
    } catch (StorageContainerException ex) {
      throw new RuntimeException(ex);
    }
    return keyValueContainer;
  }

  private void writeChunk(
      KeyValueContainer container, BlockID blockId,
      ChunkInfo chunkInfo, ByteBuffer data
  ) throws IOException {

    DispatcherContext context =
        new DispatcherContext.Builder()
            .setStage(WriteChunkStage.WRITE_DATA)
            .setTerm(1L)
            .setLogIndex(logCounter)
            .setReadFromTmpFile(false)
            .build();
    chunkManager
        .writeChunk(container, blockId, chunkInfo,
            data,
            context);

    context =
        new DispatcherContext.Builder()
            .setStage(WriteChunkStage.COMMIT_DATA)
            .setTerm(1L)
            .setLogIndex(logCounter)
            .setReadFromTmpFile(false)
            .build();
    chunkManager
        .writeChunk(container, blockId, chunkInfo,
            data,
            context);
    logCounter++;
    chunkManager.finishWriteChunks(container, new BlockData(blockId));
  }

}
