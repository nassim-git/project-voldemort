package voldemort.store.slop;

import java.util.Date;
import java.util.List;

import voldemort.VoldemortException;
import voldemort.cluster.Node;
import voldemort.routing.RoutingStrategy;
import voldemort.store.DelegatingStore;
import voldemort.store.Store;
import voldemort.versioning.Version;
import voldemort.versioning.Versioned;

import com.google.common.base.Objects;

/**
 * A delegating store that calculates ownership and stores unowned objects in a
 * special slop store for later delivery to their rightful owner.
 * 
 * This store is intended to live on the server side, and check that data the
 * server receives was properly routed.
 * 
 * @see voldemort.server.scheduler.SlopPusherJob
 * 
 * @author jay
 * 
 */
public class SlopDetectingStore extends DelegatingStore<byte[], byte[]> {

    private final int replicationFactor;
    private final Node localNode;
    private final RoutingStrategy routingStrategy;
    private final Store<byte[], Slop> slopStore;

    public SlopDetectingStore(Store<byte[], byte[]> innerStore,
                              Store<byte[], Slop> slopStore,
                              int replicationFactor,
                              Node localNode,
                              RoutingStrategy routingStrategy) {
        super(innerStore);
        this.replicationFactor = replicationFactor;
        this.localNode = Objects.nonNull(localNode);
        this.routingStrategy = Objects.nonNull(routingStrategy);
        this.slopStore = Objects.nonNull(slopStore);
    }

    private boolean isLocal(byte[] key) {
        List<Node> nodes = routingStrategy.routeRequest(key);
        int index = nodes.indexOf(localNode);
        return index >= 0 && index < replicationFactor;
    }

    @Override
    public boolean delete(byte[] key, Version version) throws VoldemortException {
        if(isLocal(key)) {
            return getInnerStore().delete(key, version);
        } else {
            Slop slop = new Slop(getName(),
                                 Slop.Operation.DELETE,
                                 key,
                                 null,
                                 localNode.getId(),
                                 new Date());
            slopStore.put(slop.makeKey(), new Versioned<Slop>(slop, version));
            return false;
        }
    }

    @Override
    public void put(byte[] key, Versioned<byte[]> value) throws VoldemortException {
        if(isLocal(key)) {
            getInnerStore().put(key, value);
        } else {
            Slop slop = new Slop(getName(),
                                 Slop.Operation.PUT,
                                 key,
                                 value.getValue(),
                                 localNode.getId(),
                                 new Date());
            slopStore.put(slop.makeKey(), new Versioned<Slop>(slop, value.getVersion()));
        }
    }

}
