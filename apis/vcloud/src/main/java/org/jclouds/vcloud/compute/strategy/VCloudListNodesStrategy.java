/**
 * Licensed to jclouds, Inc. (jclouds) under one or more
 * contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  jclouds licenses this file
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
package org.jclouds.vcloud.compute.strategy;

import static org.jclouds.compute.reference.ComputeServiceConstants.COMPUTE_LOGGER;
import static org.jclouds.compute.reference.ComputeServiceConstants.PROPERTY_BLACKLIST_NODES;

import java.util.Map;
import java.util.Set;

import javax.annotation.Resource;
import javax.inject.Named;
import javax.inject.Singleton;

import org.jclouds.compute.domain.ComputeMetadata;
import org.jclouds.compute.domain.ComputeMetadataBuilder;
import org.jclouds.compute.domain.ComputeType;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.strategy.ListNodesStrategy;
import org.jclouds.logging.Logger;
import org.jclouds.vcloud.VCloudClient;
import org.jclouds.vcloud.VCloudMediaType;
import org.jclouds.vcloud.compute.functions.FindLocationForResource;
import org.jclouds.vcloud.domain.Org;
import org.jclouds.vcloud.domain.ReferenceType;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Predicate;
import com.google.common.base.Splitter;
import com.google.common.base.Supplier;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSet.Builder;
import com.google.inject.Inject;

/**
 * @author Adrian Cole
 */
// TODO REFACTOR!!! needs to be parallel
@Singleton
public class VCloudListNodesStrategy implements ListNodesStrategy {
   @Resource
   @Named(COMPUTE_LOGGER)
   public Logger logger = Logger.NULL;

   protected final VCloudClient client;
   protected final Supplier<Map<String, ? extends Org>> nameToOrg;
   protected final VCloudGetNodeMetadataStrategy getNodeMetadata;
   protected final FindLocationForResource findLocationForResourceInVDC;

   Set<String> blackListVAppNames = ImmutableSet.<String> of();

   @Inject(optional = true)
   void setBlackList(@Named(PROPERTY_BLACKLIST_NODES) String blackListNodes) {
      if (blackListNodes != null && !"".equals(blackListNodes))
         this.blackListVAppNames = ImmutableSet.copyOf(Splitter.on(',').split(blackListNodes));
   }

   @Inject
   protected VCloudListNodesStrategy(VCloudClient client, Supplier<Map<String, ? extends Org>> nameToOrg,
         VCloudGetNodeMetadataStrategy getNodeMetadata, FindLocationForResource findLocationForResourceInVDC) {
      this.client = client;
      this.nameToOrg = nameToOrg;
      this.getNodeMetadata = getNodeMetadata;
      this.findLocationForResourceInVDC = findLocationForResourceInVDC;
   }

   @Override
   public Iterable<ComputeMetadata> listNodes() {
      Builder<ComputeMetadata> nodes = ImmutableSet.<ComputeMetadata> builder();
      for (Org org : nameToOrg.get().values()) {
         for (ReferenceType vdc : org.getVDCs().values()) {
            for (ReferenceType resource : client.getVDCClient().getVDC(vdc.getHref()).getResourceEntities().values()) {
               if (validVApp(resource)) {
                  nodes.add(convertVAppToComputeMetadata(vdc, resource));
               }
            }
         }
      }
      return nodes.build();
   }

   private boolean validVApp(ReferenceType resource) {
      return resource.getType().equals(VCloudMediaType.VAPP_XML) && !blackListVAppNames.contains(resource.getName());
   }

   private ComputeMetadata convertVAppToComputeMetadata(ReferenceType vdc, ReferenceType resource) {
      ComputeMetadataBuilder builder = new ComputeMetadataBuilder(ComputeType.NODE);
      builder.providerId(resource.getHref().toASCIIString());
      builder.name(resource.getName());
      builder.id(resource.getHref().toASCIIString());
      builder.location(findLocationForResourceInVDC.apply(vdc));
      return builder.build();
   }

   @Override
   public Iterable<NodeMetadata> listDetailsOnNodesMatching(Predicate<ComputeMetadata> filter) {
      Builder<NodeMetadata> nodes = ImmutableSet.<NodeMetadata> builder();
      for (Org org : nameToOrg.get().values()) {
         for (ReferenceType vdc : org.getVDCs().values()) {
            for (ReferenceType resource : client.getVDCClient().getVDC(vdc.getHref()).getResourceEntities().values()) {
               if (validVApp(resource) && filter.apply(convertVAppToComputeMetadata(vdc, resource))) {
                  addVAppToSetRetryingIfNotYetPresent(nodes, vdc, resource);
               }
            }
         }
      }
      return nodes.build();
   }

   @VisibleForTesting
   void addVAppToSetRetryingIfNotYetPresent(Builder<NodeMetadata> nodes, ReferenceType vdc, ReferenceType resource) {
      NodeMetadata node = null;
      int i = 0;
      while (node == null && i++ < 3) {
         try {
            node = getNodeMetadata.getNode(resource.getHref().toASCIIString());
            nodes.add(node);
         } catch (NullPointerException e) {
            logger.warn("vApp %s not yet present in vdc %s", resource.getName(), vdc.getName());
         }
      }
   }

}