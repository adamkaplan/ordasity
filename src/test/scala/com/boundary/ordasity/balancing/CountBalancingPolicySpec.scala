//
// Copyright 2011-2012, Boundary
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//

package com.boundary.ordasity.balancing

import com.codahale.logula.Logging
import org.junit.Test
import com.boundary.ordasity._
import java.util.{HashMap, UUID}
import collection.JavaConversions._
import org.apache.zookeeper.ZooKeeper
import com.twitter.common.zookeeper.ZooKeeperClient
import com.simple.simplespec.Spec

class CountBalancingPolicySpec extends Spec with Logging {
  Logging.configure()

  val config = new ClusterConfig().
    setNodeId("testNode").
    setRebalanceInterval(1).
    setDrainTime(1).
    setHosts("no_existe:2181").
    setAutoRebalance(false)

  class `Count Balancing Policy` {

    @Test def `fair share` {
      val cluster = makeCluster()
      val balancer = new CountBalancingPolicy(cluster, config)

      List("one", "two", "three", "four", "five", "six", "seven").foreach(el =>
        cluster.allWorkUnits.put(el, el))

      balancer.activeNodeSize().must(be(2))
      balancer.fairShare.must(be(4))
    }


    @Test def `rebalance if i'm overloaded` {
      val cluster = makeCluster()
      val balancer = new CountBalancingPolicy(cluster, config)
      val workUnits = List("one", "two", "three", "four", "five", "six", "seven")

      cluster.myWorkUnits.addAll(workUnits)
      workUnits.foreach(el => cluster.allWorkUnits.put(el, ""))
      workUnits.foreach(el => cluster.workUnitMap.put(el, "testNode"))

      balancer.rebalance()

      Thread.sleep(1100)
      cluster.myWorkUnits.size().must(be(4))
    }


    @Test def `chill out if things are tite` {
      val cluster = makeCluster()
      val balancer = new CountBalancingPolicy(cluster, config)
      val workUnits = List("one", "two", "three", "four", "five", "six", "seven")

      workUnits.foreach(el => cluster.allWorkUnits.put(el, ""))
      cluster.myWorkUnits.add("foo")
      cluster.workUnitMap.put("foo", "testNode")
      
      balancer.rebalance()

      Thread.sleep(1100)
      cluster.myWorkUnits.size().must(be(1))
    }


    @Test def `get max to claim` {
      val cluster = makeCluster()
      val balancer = new CountBalancingPolicy(cluster, config)
      val workUnits = List("one", "two", "three", "four", "five", "six", "seven")
      workUnits.foreach(el => cluster.allWorkUnits.put(el, ""))
      balancer.getMaxToClaim(balancer.activeNodeSize()).must(be(4))

      cluster.allWorkUnits.clear()
      balancer.getMaxToClaim(balancer.activeNodeSize()).must(be(0))

      cluster.allWorkUnits.put("one", "")
      balancer.getMaxToClaim(balancer.activeNodeSize()).must(be(1))
    }


    @Test def `claim work` {
      val cluster = makeCluster()
      val balancer = new CountBalancingPolicy(cluster, config)

      // Simulate all "create" requests succeeding.
      cluster.zk.get().create(any, any, any, any).returns("")

      val workUnits = List("one", "two", "three", "four", "five", "six", "seven")
      workUnits.foreach(el => cluster.allWorkUnits.put(el, ""))

      cluster.myWorkUnits.size().must(be(0))
      balancer.claimWork()

      // Since we're mocking the ZK client, the "all work unit" map will not be populated
      // by watches firing from ZooKeeper, so we populate our mocked map here.
      cluster.myWorkUnits.foreach(w => cluster.workUnitMap.put(w, "testNode"))

      cluster.myWorkUnits.size().must(be(4))
      balancer.getUnclaimed().size.must(be(3))
      (cluster.myWorkUnits ++ balancer.getUnclaimed()).must(be(cluster.allWorkUnits.keySet()))
    }


    @Test def `claim work with one pegged to me and one to someone else` {
      val cluster = makeCluster()
      val balancer = new CountBalancingPolicy(cluster, config)

      // Simulate all "create" requests succeeding.
      cluster.zk.get().create(any, any, any, any).returns("")

      val workUnits = List("one", "two", "three", "four", "five", "six", "seven")
      workUnits.foreach(el => cluster.allWorkUnits.put(el, ""))
      cluster.allWorkUnits.put("peggedToMe", """{"%s": "%s"}""".format(cluster.name, cluster.myNodeID))
      cluster.allWorkUnits.put("peggedToOther", """{"%s": "%s"}""".format(cluster.name, "otherNode"))

      cluster.myWorkUnits.size().must(be(0))
      balancer.getUnclaimed().size.must(be(9))
      balancer.claimWork()

      // Since we're mocking the ZK client, the "all work unit" map will not be populated
      // by watches firing from ZooKeeper, so we populate our mocked map here.
      cluster.myWorkUnits.foreach(w => cluster.workUnitMap.put(w, "testNode"))

      cluster.myWorkUnits.size().must(be(greaterThanOrEqualTo(5)))
      cluster.myWorkUnits.size().must(be(lessThanOrEqualTo(7)))
      cluster.myWorkUnits.contains("peggedToMe").must(be(true))
      cluster.myWorkUnits.contains("peggedToOther").must(be(false))

      (cluster.myWorkUnits ++ balancer.getUnclaimed()).must(be(cluster.allWorkUnits.keySet()))
    }
  }

  def makeCluster() : Cluster = {
    val cluster = new Cluster(UUID.randomUUID.toString, mock[ClusterListener], config)

    val mockZK = mock[ZooKeeper]
    val mockZKClient = mock[ZooKeeperClient]
    mockZKClient.get().returns(mockZK)
    cluster.zk = mockZKClient

    cluster.nodes = new HashMap[String, NodeInfo]
    cluster.allWorkUnits = new HashMap[String, String]
    cluster.workUnitMap = new HashMap[String, String]
    cluster.handoffRequests = new HashMap[String, String]
    cluster.handoffResults = new HashMap[String, String]
    cluster.nodes.put("foo", NodeInfo(NodeState.Started.toString, 0L))
    cluster.nodes.put("bar", NodeInfo(NodeState.Started.toString, 0L))
    cluster.nodes.put("baz", NodeInfo(NodeState.Draining.toString, 0L))
    cluster
  }

}
