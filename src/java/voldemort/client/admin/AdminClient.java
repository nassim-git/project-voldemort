/*
 * Copyright 2008-2009 LinkedIn, Inc
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package voldemort.client.admin;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;

import voldemort.VoldemortException;
import voldemort.cluster.Cluster;
import voldemort.cluster.Node;
import voldemort.serialization.VoldemortOpCode;
import voldemort.server.VoldemortServer.SERVER_STATE;
import voldemort.store.ErrorCodeMapper;
import voldemort.store.StoreDefinition;
import voldemort.store.metadata.MetadataStore;
import voldemort.store.socket.SocketAndStreams;
import voldemort.store.socket.SocketDestination;
import voldemort.store.socket.SocketPool;
import voldemort.utils.ByteUtils;
import voldemort.utils.ClusterUtils;
import voldemort.versioning.VectorClock;
import voldemort.versioning.Versioned;
import voldemort.xml.ClusterMapper;
import voldemort.xml.StoreDefinitionsMapper;

/**
 * The client implementation for Admin Client hides socket level details from
 * user
 * 
 * @author bbansal
 */
public class AdminClient {

    private static final Logger logger = Logger.getLogger(AdminClient.class);
    private final ErrorCodeMapper errorCodeMapper = new ErrorCodeMapper();

    private final Node currentNode;
    private final SocketPool pool;
    private final MetadataStore metadataStore;

    public AdminClient(Node currentNode, MetadataStore metadataStore, SocketPool socketPool) {
        this.currentNode = currentNode;
        this.metadataStore = metadataStore;
        this.pool = socketPool;
    }

    public void close() throws VoldemortException {
    // don't close the socket pool, it is shared
    }

    public void updateClusterMetaData(int nodeId, Cluster cluster, String cluster_key)
            throws VoldemortException {
        Node node = cluster.getNodeById(nodeId);
        if(node.getId() != currentNode.getId()) {
            SocketDestination destination = new SocketDestination(node.getHost(),
                                                                  node.getAdminPort());
            SocketAndStreams sands = pool.checkout(destination);
            try {
                DataOutputStream outputStream = sands.getOutputStream();
                outputStream.writeByte(VoldemortOpCode.UPDATE_CLUSTER_METADATA_OP_CODE);
                outputStream.writeUTF(cluster_key);
                String clusterString = new ClusterMapper().writeCluster(cluster);
                outputStream.writeUTF(clusterString);
                outputStream.flush();

                DataInputStream inputStream = sands.getInputStream();
                checkException(inputStream);
            } catch(IOException e) {
                close(sands.getSocket());
                throw new VoldemortException(e);
            } finally {
                pool.checkin(destination, sands);
            }
        }

    }

    public void updateStoresMetaData(int nodeId, List<StoreDefinition> storesList)
            throws VoldemortException {
        Node node = metadataStore.getCluster().getNodeById(nodeId);

        SocketDestination destination = new SocketDestination(node.getHost(), node.getAdminPort());
        SocketAndStreams sands = pool.checkout(destination);
        try {
            DataOutputStream outputStream = sands.getOutputStream();
            outputStream.writeByte(VoldemortOpCode.UPDATE_CLUSTER_METADATA_OP_CODE);
            String storeDefString = new StoreDefinitionsMapper().writeStoreList(storesList);
            outputStream.writeUTF(storeDefString);
            outputStream.flush();

            DataInputStream inputStream = sands.getInputStream();
            checkException(inputStream);
        } catch(IOException e) {
            close(sands.getSocket());
            throw new VoldemortException(e);
        } finally {
            pool.checkin(destination, sands);
        }
    }

    /**
     * Rebalances the cluster by stealing partitions from current Cluster
     * configuration. <strong> Steps </strong>
     * <ul>
     * <li>Get Current Cluster configuration from {@link MetadataStore}</li>
     * <li>update current config as {@link MetadataStore#ORIGINAL_CLUSTER_KEY}</li>
     * <li>Set Current Server state as {@link SERVER_STATE#REBALANCING_STATE}</li>
     * <li> create a new cluster config by stealing partitions from all nodes</li>
     * <li>For All nodes do
     * <ul>
     * <li> identify steal list for this node and make a temp. cluster Config</li>
     * <li> Update ALL servers with temp. cluster Config </li>
     * <li> steal partitions </li>
     * </ul>
     * </li>
     * <li>Set Current Server state as {@link SERVER_STATE#NORMAL_STATE}</li>
     * </ul>
     */
    public void stealPartitionsFromCluster(String storeName) {
        Cluster currentCluster = metadataStore.getCluster();
        updateClusterMetaData(currentNode.getId(),
                              currentCluster,
                              MetadataStore.ORIGINAL_CLUSTER_KEY);

        setRebalancingStateAndRestart(currentNode.getId());

        Cluster updatedCluster = ClusterUtils.updateClusterStealPartitions(currentCluster,
                                                                           currentNode);

        for(Node node: currentCluster.getNodes()) {
            if(node.getId() != currentNode.getId()) {

                List<Integer> stealList = getStealList(currentCluster,
                                                       updatedCluster,
                                                       node.getId(),
                                                       currentNode.getId());

                Cluster tempCluster = getTempCluster(currentCluster, node, currentNode, stealList);

                for(Node tempNode: updatedCluster.getNodes()) {
                    updateClusterMetaData(tempNode.getId(), tempCluster, MetadataStore.CLUSTER_KEY);
                }

                SocketDestination getDestination = new SocketDestination(node.getHost(),
                                                                         node.getAdminPort());
                SocketDestination putDestination = new SocketDestination(currentNode.getHost(),
                                                                         currentNode.getAdminPort());
                pipeGetAndPutStreams(getDestination, putDestination, storeName, stealList);
            }
        }
        setNormalStateAndRestart(currentNode.getId());
    }

    /**
     * Rebalances the cluster by deleting current node and returning partitions
     * to other nodes in cluster. <strong> Steps </strong>
     * <ul>
     * <li>Get Current Cluster configuration from {@link MetadataStore}</li>
     * <li>Create new Cluster config by identifying partitions to return</li>
     * <li>For All nodes do
     * <ul>
     * <li> identify steal list for this node 'K' </li>
     * <li>update current config as {@link MetadataStore#ORIGINAL_CLUSTER_KEY}
     * on remote node 'K'</li>
     * <li> create a temp cluster config </li>
     * <li> Update ALL servers with temp cluster Config </li>
     * <li>Set remote node 'K' state as {@link SERVER_STATE#REBALANCING_STATE}</li>
     * <li> return partitions </li>
     * <li> Set remote node 'K' state as {@link SERVER_STATE#NORMAL_STATE}</li>
     * </ul>
     * </li>
     * </ul>
     */
    public void returnPartitionsToCluster(String storeName) {
        Cluster currentCluster = metadataStore.getCluster();
        Cluster updatedCluster = ClusterUtils.updateClusterDeleteNode(currentCluster,
                                                                      currentNode.getId());
        for(Node node: updatedCluster.getNodes()) {
            if(node.getId() != currentNode.getId()) {
                updateClusterMetaData(node.getId(),
                                      currentCluster,
                                      MetadataStore.ORIGINAL_CLUSTER_KEY);

                List<Integer> stealList = getStealList(currentCluster,
                                                       updatedCluster,
                                                       currentNode.getId(),
                                                       node.getId());
                Cluster tempCluster = getTempCluster(currentCluster, currentNode, node, stealList);

                for(Node tempNode: tempCluster.getNodes()) {
                    updateClusterMetaData(tempNode.getId(), tempCluster, MetadataStore.CLUSTER_KEY);
                }

                setRebalancingStateAndRestart(node.getId());

                SocketDestination getDestination = new SocketDestination(currentNode.getHost(),
                                                                         currentNode.getAdminPort());
                SocketDestination putDestination = new SocketDestination(node.getHost(),
                                                                         node.getAdminPort());
                pipeGetAndPutStreams(getDestination, putDestination, storeName, stealList);

                setNormalStateAndRestart(node.getId());
            }
        }
    }

    public void restartServices(int nodeId) {
        Cluster currentCluster = metadataStore.getCluster();
        Node node = currentCluster.getNodeById(nodeId);
        SocketDestination destination = new SocketDestination(node.getHost(), node.getAdminPort());

        SocketAndStreams sands = pool.checkout(destination);
        try {
            DataOutputStream outputStream = sands.getOutputStream();
            outputStream.writeByte(VoldemortOpCode.RESTART_SERVICES_OP_CODE);
            outputStream.flush();

            DataInputStream inputStream = sands.getInputStream();
            checkException(inputStream);
        } catch(IOException e) {
            close(sands.getSocket());
            throw new VoldemortException(e);
        } finally {
            pool.checkin(destination, sands);
        }
    }

    public void setNormalStateAndRestart(int nodeId) {
        Cluster currentCluster = metadataStore.getCluster();
        Node node = currentCluster.getNodeById(nodeId);
        SocketDestination destination = new SocketDestination(node.getHost(), node.getAdminPort());

        SocketAndStreams sands = pool.checkout(destination);
        try {
            DataOutputStream outputStream = sands.getOutputStream();
            outputStream.writeByte(VoldemortOpCode.NORMAL_SERVER_MODE_OP_CODE);
            outputStream.flush();

            DataInputStream inputStream = sands.getInputStream();
            checkException(inputStream);
        } catch(IOException e) {
            close(sands.getSocket());
            throw new VoldemortException(e);
        } finally {
            pool.checkin(destination, sands);
        }

        // restart current node
        restartServices(nodeId);
    }

    public void setRebalancingStateAndRestart(int nodeId) {
        Cluster currentCluster = metadataStore.getCluster();
        Node node = currentCluster.getNodeById(nodeId);
        SocketDestination destination = new SocketDestination(node.getHost(), node.getAdminPort());

        SocketAndStreams sands = pool.checkout(destination);
        try {
            DataOutputStream outputStream = sands.getOutputStream();
            outputStream.writeByte(VoldemortOpCode.REBALANCING_SERVER_MODE_OP_CODE);
            outputStream.flush();

            DataInputStream inputStream = sands.getInputStream();
            checkException(inputStream);
        } catch(IOException e) {
            close(sands.getSocket());
            throw new VoldemortException(e);
        } finally {
            pool.checkin(destination, sands);
        }

        // restart current node
        restartServices(nodeId);
    }

    public List<Versioned<byte[]>> redirectGet(int redirectedNodeId, String storeName, byte[] key) {
        Node redirectedNode = metadataStore.getCluster().getNodeById(redirectedNodeId);
        SocketDestination destination = new SocketDestination(redirectedNode.getHost(),
                                                              redirectedNode.getAdminPort());
        SocketAndStreams sands = pool.checkout(destination);
        try {
            DataOutputStream outputStream = sands.getOutputStream();
            outputStream.writeByte(VoldemortOpCode.REDIRECT_GET_OP_CODE);
            outputStream.writeUTF(storeName);
            outputStream.writeInt(key.length);
            outputStream.write(key);
            outputStream.flush();
            DataInputStream inputStream = sands.getInputStream();
            checkException(inputStream);
            int resultSize = inputStream.readInt();
            List<Versioned<byte[]>> results = new ArrayList<Versioned<byte[]>>(resultSize);
            for(int i = 0; i < resultSize; i++) {
                int valueSize = inputStream.readInt();
                byte[] bytes = new byte[valueSize];
                ByteUtils.read(inputStream, bytes);
                VectorClock clock = new VectorClock(bytes);
                results.add(new Versioned<byte[]>(ByteUtils.copy(bytes,
                                                                 clock.sizeInBytes(),
                                                                 bytes.length), clock));
            }
            return results;
        } catch(IOException e) {
            close(sands.getSocket());
            throw new VoldemortException(e);
        } finally {
            pool.checkin(destination, sands);
        }
    }

    private Cluster getTempCluster(Cluster currentCluster,
                                   Node fromNode,
                                   Node toNode,
                                   List<Integer> stealList) {
        ArrayList<Node> nodes = new ArrayList<Node>();
        for(Node node: currentCluster.getNodes()) {
            if(fromNode.getId() == node.getId()) {
                List<Integer> partitionList = new ArrayList<Integer>(node.getPartitionIds());
                partitionList.removeAll(stealList);
                nodes.add(new Node(node.getId(),
                                   node.getHost(),
                                   node.getHttpPort(),
                                   node.getSocketPort(),
                                   node.getAdminPort(),
                                   stealList,
                                   node.getStatus()));
            } else if(toNode.getId() == node.getId()) {
                stealList.addAll(node.getPartitionIds());
                nodes.add(new Node(node.getId(),
                                   node.getHost(),
                                   node.getHttpPort(),
                                   node.getSocketPort(),
                                   node.getAdminPort(),
                                   stealList,
                                   node.getStatus()));
            } else {
                nodes.add(node);
            }
        }
        return new Cluster(currentCluster.getName(), nodes);
    }

    private void pipeGetAndPutStreams(SocketDestination getDestination,
                                      SocketDestination putDestination,
                                      String storeName,
                                      List<Integer> stealList) {
        SocketAndStreams sands = pool.checkout(getDestination);
        SocketAndStreams sands2 = pool.checkout(putDestination);

        try {
            // get these partitions from the node for store
            DataOutputStream getOutputStream = sands.getOutputStream();
            DataInputStream getInputStream = sands.getInputStream();

            DataOutputStream putOutputStream = sands2.getOutputStream();
            DataInputStream putInputStream = sands2.getInputStream();

            // send request for get Partition List
            getOutputStream.writeByte(VoldemortOpCode.GET_PARTITION_AS_STREAM_OP_CODE);
            getOutputStream.writeUTF(storeName);
            getOutputStream.writeInt(stealList.size());
            for(Integer p: stealList) {
                getOutputStream.writeInt(p.intValue());
            }
            getOutputStream.flush();

            // send request for putPartition
            putOutputStream.writeByte(VoldemortOpCode.PUT_PARTITION_AS_STREAM_OP_CODE);
            putOutputStream.writeUTF(storeName);

            // pipe
            int keySize = getInputStream.readInt();
            while(keySize != -1) {
                putOutputStream.writeInt(keySize);
                byte[] key = new byte[keySize];
                ByteUtils.read(getInputStream, key);
                putOutputStream.write(key);

                int valueSize = getInputStream.readInt();
                putOutputStream.writeInt(valueSize);
                byte[] value = new byte[valueSize];
                ByteUtils.read(getInputStream, value);
                putOutputStream.write(value);

                keySize = getInputStream.readInt();
            }
            putOutputStream.writeInt(-1); // end this stream here

            checkException(getInputStream);
            checkException(putInputStream);
        } catch(IOException e) {
            close(sands.getSocket());
            close(sands2.getSocket());
            throw new VoldemortException(e);
        } finally {
            pool.checkin(getDestination, sands);
            pool.checkin(putDestination, sands2);
        }
    }

    private List<Integer> getStealList(Cluster old, Cluster updated, int fromNode, int toNode) {
        ArrayList<Integer> stealList = new ArrayList<Integer>();
        List<Integer> oldPartitions = old.getNodeById(fromNode).getPartitionIds();
        List<Integer> updatedPartitions = updated.getNodeById(toNode).getPartitionIds();

        for(Integer p: updatedPartitions) {
            if(oldPartitions.contains(p)) {
                stealList.add(p);
            }
        }
        return stealList;
    }

    private void checkException(DataInputStream inputStream) throws IOException {
        short retCode = inputStream.readShort();
        if(retCode != 0) {
            String error = inputStream.readUTF();
            throw errorCodeMapper.getError(retCode, error);
        }
    }

    private void close(Socket socket) {
        try {
            socket.close();
        } catch(IOException e) {
            logger.warn("Failed to close socket");
        }
    }

}
