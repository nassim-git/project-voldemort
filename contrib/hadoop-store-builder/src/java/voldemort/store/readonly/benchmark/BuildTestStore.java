package voldemort.store.readonly.benchmark;

import java.io.File;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.BytesWritable;
import org.apache.hadoop.mapred.SequenceFileInputFormat;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

import voldemort.cluster.Cluster;
import voldemort.store.StoreDefinition;
import voldemort.store.readonly.mr.AbstractHadoopStoreBuilderMapper;
import voldemort.store.readonly.mr.HadoopStoreBuilder;
import voldemort.utils.ByteUtils;
import voldemort.utils.Utils;
import voldemort.xml.ClusterMapper;
import voldemort.xml.StoreDefinitionsMapper;

/**
 * Build a test store from the generated data
 * 
 * @author jay
 * 
 */
public class BuildTestStore extends Configured implements Tool {

    public static void main(String[] args) throws Exception {
        int res = ToolRunner.run(new Configuration(), new BuildTestStore(), args);
        System.exit(res);
    }

    public int run(String[] args) throws Exception {
        if(args.length != 5)
            Utils.croak("Expected arguments store_name config_dir temp_dir input_path output_path");
        String storeName = args[0];
        String configDir = args[1];
        String tempDir = args[2];
        String inputDir = args[3];
        String outputDir = args[4];

        List<StoreDefinition> storeDefs = new StoreDefinitionsMapper().readStoreList(new File(configDir,
                                                                                              "stores.xml"));
        StoreDefinition def = null;
        for(StoreDefinition d: storeDefs)
            if(d.getName().equals(storeName))
                def = d;
        Cluster cluster = new ClusterMapper().readCluster(new File(configDir, "cluster.xml"));

        Configuration config = this.getConf();
        config.set("mapred.job.name", "test-store-builder");
        HadoopStoreBuilder builder = new HadoopStoreBuilder(config,
                                                            BuildTestStoreMapper.class,
                                                            SequenceFileInputFormat.class,
                                                            cluster,
                                                            def,
                                                            2,
                                                            (long) (1.5 * 1024 * 1024 * 1024),
                                                            new Path(tempDir),
                                                            new Path(outputDir),
                                                            new Path(inputDir));
        builder.build();
        return 0;
    }

    public static class BuildTestStoreMapper extends
            AbstractHadoopStoreBuilderMapper<BytesWritable, BytesWritable> {

        @Override
        public Object makeKey(BytesWritable key, BytesWritable value) {
            return getValid(key);
        }

        @Override
        public Object makeValue(BytesWritable key, BytesWritable value) {
            return getValid(value);
        }

        private byte[] getValid(BytesWritable writable) {
            if(writable.getSize() == writable.getCapacity())
                return writable.get();
            else
                return ByteUtils.copy(writable.get(), 0, writable.getSize());
        }

    }

}
