package au.edu.csdila.common.geoutils;

/*
 * Copyright (C) 2012 amacaulay & 2014 Benny Chen
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.data.simple.SimpleFeatureSource;
import org.geotools.feature.DefaultFeatureCollection;
import org.opengis.feature.simple.SimpleFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Generates network buffers for a set of points, using Fork/Join for concurrency
 * Benny's improvement: using cached network (SimpleFeatureCollection) to speed up the processing
 * @author amacaulay & Benny Chen
 */
public class NetworkBufferBatch { //extends RecursiveAction {

  // /**
  // * Some nonsense to satisfy sonar+findbugs
  // */
  // private static final long serialVersionUID = 1L;
  static final Logger LOGGER = LoggerFactory
      .getLogger(NetworkBufferBatch.class);
  private SimpleFeatureCollection network;
  private SimpleFeatureCollection points;
  private DefaultFeatureCollection buffers;
  private DefaultFeatureCollection graphs;
  private Double distance;
  private Double bufferSize;
  private int pointsPerThread;

  /**
   * Generates network buffers for a set of points
   * 
   * @param network
   *          The network to use to generate service networks
   * @param points
   *          The set of points of interest
   * @param distance
   *          The distance to traverse along the network.
   * @param bufferSize
   *          The length to buffer the service network
   */
  public NetworkBufferBatch(SimpleFeatureCollection network,
      SimpleFeatureCollection points, Double distance, Double bufferSize) {
    this.network = network;
    this.points = points;
    this.distance = distance;
    this.bufferSize = bufferSize;
    this.buffers = new DefaultFeatureCollection();
    this.graphs = new DefaultFeatureCollection();
    this.pointsPerThread = 1000; // TODO: make this dynamic
  }

  /**
   * 
   * @return A SimpleFeatureCollection of the service area networks for all
   *         points of interest
   */
  public SimpleFeatureCollection getGraphs() {
    return graphs;
  }

  /**
   * 
   * @return A SimpleFeatureCollection consisting of the buffered service areas
   *         for each point of interest
   */
  public SimpleFeatureCollection createBuffers() {
    ExecutorService executorService = Executors.newFixedThreadPool(Runtime
        .getRuntime().availableProcessors());
    
    try {
    List<Future> futures = new ArrayList<Future>();
    int count = 0;
    SimpleFeatureIterator features = points.features();
    while (features.hasNext()) {
        LOGGER.debug("Buffer count {}", ++count);
        SimpleFeature point = features.next();
        
        //ATTENTION: if the SimpleFeatureCollection network is provided directly to a concurrency method, its SimpleFeatureIterator will get messed and the output is unpredicatable.
        //A workaround is to create a copy (new) of network and feed that newly created copy to each thread. 
        
        DefaultFeatureCollection tmpNetwork =  new DefaultFeatureCollection();
        SimpleFeatureIterator iterator = network.features();

		 while (iterator.hasNext()) 
	     {
			 tmpNetwork.add(iterator.next());
		 }
		 iterator.close();
		 
        Buffernator ac = new Buffernator(point, tmpNetwork);
        Future future = executorService.submit(ac);
        futures.add(future);
    }
    features.close();
    for (Future future : futures) {
      try {
        buffers.add((SimpleFeature)(future.get()));
        LOGGER.debug("Completing Buffer");
      } catch (ExecutionException e) {
        LOGGER.error("Buffer generation failed for a point", e);
      }
    }
    LOGGER.debug("Completed {} buffers for {} points", buffers.size(), points.size());
    } catch (InterruptedException e) {
      throw new IllegalStateException(e);
    } finally {
      executorService.shutdownNow();
    }
    
    return buffers;
  }

  class Buffernator implements Callable<SimpleFeature> {
    private SimpleFeature point;
    private SimpleFeatureCollection network;

    Buffernator(SimpleFeature point, SimpleFeatureCollection network) {
      this.point = point;
      this.network = network;
    }

    public SimpleFeature call() throws Exception {
      LOGGER.debug("Calculating service network");
      Map serviceArea = NetworkBuffer.findServiceArea(network, point, distance,
          bufferSize);
      
      LOGGER.debug("Buffering service network");
      SimpleFeature networkBuffer = NetworkBuffer.createBufferFromEdges(
          serviceArea, bufferSize, point, String.valueOf(point.getID()));
      // if (networkBuffer != null) {
      return networkBuffer;
    }
  }
}
