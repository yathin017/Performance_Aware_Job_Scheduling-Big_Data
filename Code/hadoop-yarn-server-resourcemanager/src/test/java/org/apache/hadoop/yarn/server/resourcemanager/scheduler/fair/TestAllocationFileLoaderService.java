/**
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/
package org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair;

import static org.junit.Assert.*;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.yarn.api.records.QueueACL;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.QueuePlacementRule.NestedUserQueue;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.policies.DominantResourceFairnessPolicy;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.policies.FairSharePolicy;
import org.apache.hadoop.yarn.util.ControlledClock;
import org.apache.hadoop.yarn.util.resource.Resources;
import org.junit.Test;

public class TestAllocationFileLoaderService {
  
  final static String TEST_DIR = new File(System.getProperty("test.build.data",
      "/tmp")).getAbsolutePath();

  final static String ALLOC_FILE = new File(TEST_DIR,
      "test-queues").getAbsolutePath();
  
  @Test
  public void testGetAllocationFileFromClasspath() {
    Configuration conf = new Configuration();
    conf.set(FairSchedulerConfiguration.ALLOCATION_FILE,
        "test-fair-scheduler.xml");
    AllocationFileLoaderService allocLoader = new AllocationFileLoaderService();
    File allocationFile = allocLoader.getAllocationFile(conf);
    assertEquals("test-fair-scheduler.xml", allocationFile.getName());
    assertTrue(allocationFile.exists());
  }
  
  @Test (timeout = 10000)
  public void testReload() throws Exception {
    PrintWriter out = new PrintWriter(new FileWriter(ALLOC_FILE));
    out.println("<?xml version=\"1.0\"?>");
    out.println("<allocations>");
    out.println("  <queue name=\"queueA\">");
    out.println("    <maxRunningApps>1</maxRunningApps>");
    out.println("  </queue>");
    out.println("  <queue name=\"queueB\" />");
    out.println("  <queuePlacementPolicy>");
    out.println("    <rule name='default' />");
    out.println("  </queuePlacementPolicy>");
    out.println("</allocations>");
    out.close();
    
    ControlledClock clock = new ControlledClock();
    clock.setTime(0);
    Configuration conf = new Configuration();
    conf.set(FairSchedulerConfiguration.ALLOCATION_FILE, ALLOC_FILE);

    AllocationFileLoaderService allocLoader = new AllocationFileLoaderService(
        clock);
    allocLoader.reloadIntervalMs = 5;
    allocLoader.init(conf);
    ReloadListener confHolder = new ReloadListener();
    allocLoader.setReloadListener(confHolder);
    allocLoader.reloadAllocations();
    AllocationConfiguration allocConf = confHolder.allocConf;
    
    // Verify conf
    QueuePlacementPolicy policy = allocConf.getPlacementPolicy();
    List<QueuePlacementRule> rules = policy.getRules();
    assertEquals(1, rules.size());
    assertEquals(QueuePlacementRule.Default.class, rules.get(0).getClass());
    assertEquals(1, allocConf.getQueueMaxApps("root.queueA"));
    assertEquals(2, allocConf.getConfiguredQueues().get(FSQueueType.LEAF)
        .size());
    assertTrue(allocConf.getConfiguredQueues().get(FSQueueType.LEAF)
        .contains("root.queueA"));
    assertTrue(allocConf.getConfiguredQueues().get(FSQueueType.LEAF)
        .contains("root.queueB"));
    
    confHolder.allocConf = null;
    
    // Modify file and advance the clock
    out = new PrintWriter(new FileWriter(ALLOC_FILE));
    out.println("<?xml version=\"1.0\"?>");
    out.println("<allocations>");
    out.println("  <queue name=\"queueB\">");
    out.println("    <maxRunningApps>3</maxRunningApps>");
    out.println("  </queue>");
    out.println("  <queuePlacementPolicy>");
    out.println("    <rule name='specified' />");
    out.println("    <rule name='nestedUserQueue' >");  
    out.println("         <rule name='primaryGroup' />");
    out.println("    </rule>");
    out.println("    <rule name='default' />");
    out.println("  </queuePlacementPolicy>");
    out.println("</allocations>");
    out.close();
    
    clock.tickMsec(System.currentTimeMillis()
        + AllocationFileLoaderService.ALLOC_RELOAD_WAIT_MS + 10000);
    allocLoader.start();
    
    while (confHolder.allocConf == null) {
      Thread.sleep(20);
    }
    
    // Verify conf
    allocConf = confHolder.allocConf;
    policy = allocConf.getPlacementPolicy();
    rules = policy.getRules();
    assertEquals(3, rules.size());
    assertEquals(QueuePlacementRule.Specified.class, rules.get(0).getClass());
    assertEquals(QueuePlacementRule.NestedUserQueue.class, rules.get(1)
        .getClass());
    assertEquals(QueuePlacementRule.PrimaryGroup.class,
        ((NestedUserQueue) (rules.get(1))).nestedRule.getClass());
    assertEquals(QueuePlacementRule.Default.class, rules.get(2).getClass());
    assertEquals(3, allocConf.getQueueMaxApps("root.queueB"));
    assertEquals(1, allocConf.getConfiguredQueues().get(FSQueueType.LEAF)
        .size());
    assertTrue(allocConf.getConfiguredQueues().get(FSQueueType.LEAF)
        .contains("root.queueB"));
  }
  
  @Test
  public void testAllocationFileParsing() throws Exception {
    Configuration conf = new Configuration();
    conf.set(FairSchedulerConfiguration.ALLOCATION_FILE, ALLOC_FILE);
    AllocationFileLoaderService allocLoader = new AllocationFileLoaderService();

    PrintWriter out = new PrintWriter(new FileWriter(ALLOC_FILE));
    out.println("<?xml version=\"1.0\"?>");
    out.println("<allocations>");
    // Give queue A a minimum of 1024 M
    out.println("<queue name=\"queueA\">");
    out.println("<minResources>1024mb,0vcores</minResources>");
    out.println("<maxResources>2048mb,10vcores</maxResources>");
    out.println("</queue>");
    // Give queue B a minimum of 2048 M
    out.println("<queue name=\"queueB\">");
    out.println("<minResources>2048mb,0vcores</minResources>");
    out.println("<maxResources>5120mb,110vcores</maxResources>");
    out.println("<aclAdministerApps>alice,bob admins</aclAdministerApps>");
    out.println("<schedulingPolicy>fair</schedulingPolicy>");
    out.println("</queue>");
    // Give queue C no minimum
    out.println("<queue name=\"queueC\">");
    out.println("<minResources>5120mb,0vcores</minResources>");
    out.println("<aclSubmitApps>alice,bob admins</aclSubmitApps>");
    out.println("</queue>");
    // Give queue D a limit of 3 running apps and 0.4f maxAMShare
    out.println("<queue name=\"queueD\">");
    out.println("<maxRunningApps>3</maxRunningApps>");
    out.println("<maxAMShare>0.4</maxAMShare>");
    out.println("</queue>");
    // Give queue E a preemption timeout of one minute
    out.println("<queue name=\"queueE\">");
    out.println("<minSharePreemptionTimeout>60</minSharePreemptionTimeout>");
    out.println("</queue>");
    // Make queue F a parent queue without configured leaf queues using the
    // 'type' attribute
    out.println("<queue name=\"queueF\" type=\"parent\" >");
    out.println("<maxChildResources>2048mb,64vcores</maxChildResources>");
    out.println("</queue>");
    // Create hierarchical queues G,H, with different min/fair share preemption
    // timeouts and preemption thresholds. Also add a child default to make sure
    // it doesn't impact queue H.
    out.println("<queue name=\"queueG\">");
    out.println("<maxChildResources>2048mb,64vcores</maxChildResources>");
    out.println("<fairSharePreemptionTimeout>120</fairSharePreemptionTimeout>");
    out.println("<minSharePreemptionTimeout>50</minSharePreemptionTimeout>");
    out.println("<fairSharePreemptionThreshold>0.6</fairSharePreemptionThreshold>");
    out.println("   <queue name=\"queueH\">");
    out.println("   <fairSharePreemptionTimeout>180</fairSharePreemptionTimeout>");
    out.println("   <minSharePreemptionTimeout>40</minSharePreemptionTimeout>");
    out.println("   <fairSharePreemptionThreshold>0.7</fairSharePreemptionThreshold>");
    out.println("   </queue>");
    out.println("</queue>");
    // Set default limit of apps per queue to 15
    out.println("<queueMaxAppsDefault>15</queueMaxAppsDefault>");
    // Set default limit of max resource per queue to 4G and 100 cores
    out.println("<queueMaxResourcesDefault>4096mb,100vcores</queueMaxResourcesDefault>");
    // Set default limit of apps per user to 5
    out.println("<userMaxAppsDefault>5</userMaxAppsDefault>");
    // Set default limit of AMResourceShare to 0.5f
    out.println("<queueMaxAMShareDefault>0.5f</queueMaxAMShareDefault>");
    // Give user1 a limit of 10 jobs
    out.println("<user name=\"user1\">");
    out.println("<maxRunningApps>10</maxRunningApps>");
    out.println("</user>");
    // Set default min share preemption timeout to 2 minutes
    out.println("<defaultMinSharePreemptionTimeout>120"
        + "</defaultMinSharePreemptionTimeout>");
    // Set default fair share preemption timeout to 5 minutes
    out.println("<defaultFairSharePreemptionTimeout>300</defaultFairSharePreemptionTimeout>");
    // Set default fair share preemption threshold to 0.4
    out.println("<defaultFairSharePreemptionThreshold>0.4</defaultFairSharePreemptionThreshold>");
    // Set default scheduling policy to DRF
    out.println("<defaultQueueSchedulingPolicy>drf</defaultQueueSchedulingPolicy>");
    out.println("</allocations>");
    out.close();
    
    allocLoader.init(conf);
    ReloadListener confHolder = new ReloadListener();
    allocLoader.setReloadListener(confHolder);
    allocLoader.reloadAllocations();
    AllocationConfiguration queueConf = confHolder.allocConf;
    
    assertEquals(6, queueConf.getConfiguredQueues().get(FSQueueType.LEAF).size());
    assertEquals(Resources.createResource(0),
        queueConf.getMinResources("root." + YarnConfiguration.DEFAULT_QUEUE_NAME));
    assertEquals(Resources.createResource(0),
        queueConf.getMinResources("root." + YarnConfiguration.DEFAULT_QUEUE_NAME));

    assertEquals(Resources.createResource(2048, 10),
        queueConf.getMaxResources("root.queueA").getResource());
    assertEquals(Resources.createResource(5120, 110),
        queueConf.getMaxResources("root.queueB").getResource());
    assertEquals(Resources.createResource(4096, 100),
        queueConf.getMaxResources("root.queueC").getResource());
    assertEquals(Resources.createResource(4096, 100),
        queueConf.getMaxResources("root.queueD").getResource());
    assertEquals(Resources.createResource(4096, 100),
        queueConf.getMaxResources("root.queueE").getResource());
    assertEquals(Resources.createResource(4096, 100),
        queueConf.getMaxResources("root.queueF").getResource());
    assertEquals(Resources.createResource(4096, 100),
        queueConf.getMaxResources("root.queueG").getResource());
    assertEquals(Resources.createResource(4096, 100),
        queueConf.getMaxResources("root.queueG.queueH").getResource());

    assertEquals(Resources.createResource(1024, 0),
        queueConf.getMinResources("root.queueA"));
    assertEquals(Resources.createResource(2048, 0),
        queueConf.getMinResources("root.queueB"));
    assertEquals(Resources.createResource(5120, 0),
        queueConf.getMinResources("root.queueC"));
    assertEquals(Resources.createResource(0),
        queueConf.getMinResources("root.queueD"));
    assertEquals(Resources.createResource(0),
        queueConf.getMinResources("root.queueE"));
    assertEquals(Resources.createResource(0),
        queueConf.getMinResources("root.queueF"));
    assertEquals(Resources.createResource(0),
        queueConf.getMinResources("root.queueG"));
    assertEquals(Resources.createResource(0),
        queueConf.getMinResources("root.queueG.queueH"));

    assertNull("Max child resources unexpectedly set for queue root.queueA",
        queueConf.getMaxChildResources("root.queueA"));
    assertNull("Max child resources unexpectedly set for queue root.queueB",
        queueConf.getMaxChildResources("root.queueB"));
    assertNull("Max child resources unexpectedly set for queue root.queueC",
        queueConf.getMaxChildResources("root.queueC"));
    assertNull("Max child resources unexpectedly set for queue root.queueD",
        queueConf.getMaxChildResources("root.queueD"));
    assertNull("Max child resources unexpectedly set for queue root.queueE",
        queueConf.getMaxChildResources("root.queueE"));
    assertEquals(Resources.createResource(2048, 64),
        queueConf.getMaxChildResources("root.queueF").getResource());
    assertEquals(Resources.createResource(2048, 64),
        queueConf.getMaxChildResources("root.queueG").getResource());
    assertNull("Max child resources unexpectedly set for "
        + "queue root.queueG.queueH",
        queueConf.getMaxChildResources("root.queueG.queueH"));

    assertEquals(15, queueConf.getQueueMaxApps("root."
        + YarnConfiguration.DEFAULT_QUEUE_NAME));
    assertEquals(15, queueConf.getQueueMaxApps("root.queueA"));
    assertEquals(15, queueConf.getQueueMaxApps("root.queueB"));
    assertEquals(15, queueConf.getQueueMaxApps("root.queueC"));
    assertEquals(3, queueConf.getQueueMaxApps("root.queueD"));
    assertEquals(15, queueConf.getQueueMaxApps("root.queueE"));
    assertEquals(10, queueConf.getUserMaxApps("user1"));
    assertEquals(5, queueConf.getUserMaxApps("user2"));

    assertEquals(.5f, queueConf.getQueueMaxAMShare("root." + YarnConfiguration.DEFAULT_QUEUE_NAME), 0.01);
    assertEquals(.5f, queueConf.getQueueMaxAMShare("root.queueA"), 0.01);
    assertEquals(.5f, queueConf.getQueueMaxAMShare("root.queueB"), 0.01);
    assertEquals(.5f, queueConf.getQueueMaxAMShare("root.queueC"), 0.01);
    assertEquals(.4f, queueConf.getQueueMaxAMShare("root.queueD"), 0.01);
    assertEquals(.5f, queueConf.getQueueMaxAMShare("root.queueE"), 0.01);

    // Root should get * ACL
    assertEquals("*", queueConf.getQueueAcl("root",
        QueueACL.ADMINISTER_QUEUE).getAclString());
    assertEquals("*", queueConf.getQueueAcl("root",
        QueueACL.SUBMIT_APPLICATIONS).getAclString());

    // Unspecified queues should get default ACL
    assertEquals(" ", queueConf.getQueueAcl("root.queueA",
        QueueACL.ADMINISTER_QUEUE).getAclString());
    assertEquals(" ", queueConf.getQueueAcl("root.queueA",
        QueueACL.SUBMIT_APPLICATIONS).getAclString());

    // Queue B ACL
    assertEquals("alice,bob admins", queueConf.getQueueAcl("root.queueB",
        QueueACL.ADMINISTER_QUEUE).getAclString());

    // Queue C ACL
    assertEquals("alice,bob admins", queueConf.getQueueAcl("root.queueC",
        QueueACL.SUBMIT_APPLICATIONS).getAclString());

    assertEquals(120000, queueConf.getMinSharePreemptionTimeout("root"));
    assertEquals(-1, queueConf.getMinSharePreemptionTimeout("root." +
        YarnConfiguration.DEFAULT_QUEUE_NAME));
    assertEquals(-1, queueConf.getMinSharePreemptionTimeout("root.queueA"));
    assertEquals(-1, queueConf.getMinSharePreemptionTimeout("root.queueB"));
    assertEquals(-1, queueConf.getMinSharePreemptionTimeout("root.queueC"));
    assertEquals(-1, queueConf.getMinSharePreemptionTimeout("root.queueD"));
    assertEquals(60000, queueConf.getMinSharePreemptionTimeout("root.queueE"));
    assertEquals(-1, queueConf.getMinSharePreemptionTimeout("root.queueF"));
    assertEquals(50000, queueConf.getMinSharePreemptionTimeout("root.queueG"));
    assertEquals(40000, queueConf.getMinSharePreemptionTimeout("root.queueG.queueH"));

    assertEquals(300000, queueConf.getFairSharePreemptionTimeout("root"));
    assertEquals(-1, queueConf.getFairSharePreemptionTimeout("root." +
        YarnConfiguration.DEFAULT_QUEUE_NAME));
    assertEquals(-1, queueConf.getFairSharePreemptionTimeout("root.queueA"));
    assertEquals(-1, queueConf.getFairSharePreemptionTimeout("root.queueB"));
    assertEquals(-1, queueConf.getFairSharePreemptionTimeout("root.queueC"));
    assertEquals(-1, queueConf.getFairSharePreemptionTimeout("root.queueD"));
    assertEquals(-1, queueConf.getFairSharePreemptionTimeout("root.queueE"));
    assertEquals(-1, queueConf.getFairSharePreemptionTimeout("root.queueF"));
    assertEquals(120000, queueConf.getFairSharePreemptionTimeout("root.queueG"));
    assertEquals(180000, queueConf.getFairSharePreemptionTimeout("root.queueG.queueH"));

    assertEquals(.4f, queueConf.getFairSharePreemptionThreshold("root"), 0.01);
    assertEquals(-1, queueConf.getFairSharePreemptionThreshold("root." +
        YarnConfiguration.DEFAULT_QUEUE_NAME), 0.01);
    assertEquals(-1,
        queueConf.getFairSharePreemptionThreshold("root.queueA"), 0.01);
    assertEquals(-1,
        queueConf.getFairSharePreemptionThreshold("root.queueB"), 0.01);
    assertEquals(-1,
        queueConf.getFairSharePreemptionThreshold("root.queueC"), 0.01);
    assertEquals(-1,
        queueConf.getFairSharePreemptionThreshold("root.queueD"), 0.01);
    assertEquals(-1,
        queueConf.getFairSharePreemptionThreshold("root.queueE"), 0.01);
    assertEquals(-1,
        queueConf.getFairSharePreemptionThreshold("root.queueF"), 0.01);
    assertEquals(.6f,
        queueConf.getFairSharePreemptionThreshold("root.queueG"), 0.01);
    assertEquals(.7f,
        queueConf.getFairSharePreemptionThreshold("root.queueG.queueH"), 0.01);

    assertTrue(queueConf.getConfiguredQueues()
        .get(FSQueueType.PARENT)
        .contains("root.queueF"));
    assertTrue(queueConf.getConfiguredQueues().get(FSQueueType.PARENT)
        .contains("root.queueG"));
    assertTrue(queueConf.getConfiguredQueues().get(FSQueueType.LEAF)
        .contains("root.queueG.queueH"));

    // Verify existing queues have default scheduling policy
    assertEquals(DominantResourceFairnessPolicy.NAME,
        queueConf.getSchedulingPolicy("root").getName());
    assertEquals(DominantResourceFairnessPolicy.NAME,
        queueConf.getSchedulingPolicy("root.queueA").getName());
    // Verify default is overriden if specified explicitly
    assertEquals(FairSharePolicy.NAME,
        queueConf.getSchedulingPolicy("root.queueB").getName());
    // Verify new queue gets default scheduling policy
    assertEquals(DominantResourceFairnessPolicy.NAME,
        queueConf.getSchedulingPolicy("root.newqueue").getName());
  }
  
  @Test
  public void testBackwardsCompatibleAllocationFileParsing() throws Exception {
    Configuration conf = new Configuration();
    conf.set(FairSchedulerConfiguration.ALLOCATION_FILE, ALLOC_FILE);
    AllocationFileLoaderService allocLoader = new AllocationFileLoaderService();

    PrintWriter out = new PrintWriter(new FileWriter(ALLOC_FILE));
    out.println("<?xml version=\"1.0\"?>");
    out.println("<allocations>");
    // Give queue A a minimum of 1024 M
    out.println("<pool name=\"queueA\">");
    out.println("<minResources>1024mb,0vcores</minResources>");
    out.println("</pool>");
    // Give queue B a minimum of 2048 M
    out.println("<pool name=\"queueB\">");
    out.println("<minResources>2048mb,0vcores</minResources>");
    out.println("<aclAdministerApps>alice,bob admins</aclAdministerApps>");
    out.println("</pool>");
    // Give queue C no minimum
    out.println("<pool name=\"queueC\">");
    out.println("<aclSubmitApps>alice,bob admins</aclSubmitApps>");
    out.println("</pool>");
    // Give queue D a limit of 3 running apps
    out.println("<pool name=\"queueD\">");
    out.println("<maxRunningApps>3</maxRunningApps>");
    out.println("</pool>");
    // Give queue E a preemption timeout of one minute and 0.3f threshold
    out.println("<pool name=\"queueE\">");
    out.println("<minSharePreemptionTimeout>60</minSharePreemptionTimeout>");
    out.println("<fairSharePreemptionThreshold>0.3</fairSharePreemptionThreshold>");
    out.println("</pool>");
    // Set default limit of apps per queue to 15
    out.println("<queueMaxAppsDefault>15</queueMaxAppsDefault>");
    // Set default limit of apps per user to 5
    out.println("<userMaxAppsDefault>5</userMaxAppsDefault>");
    // Give user1 a limit of 10 jobs
    out.println("<user name=\"user1\">");
    out.println("<maxRunningApps>10</maxRunningApps>");
    out.println("</user>");
    // Set default min share preemption timeout to 2 minutes
    out.println("<defaultMinSharePreemptionTimeout>120"
        + "</defaultMinSharePreemptionTimeout>");
    // Set fair share preemption timeout to 5 minutes
    out.println("<fairSharePreemptionTimeout>300</fairSharePreemptionTimeout>");
    // Set default fair share preemption threshold to 0.6f
    out.println("<defaultFairSharePreemptionThreshold>0.6</defaultFairSharePreemptionThreshold>");
    out.println("</allocations>");
    out.close();
    
    allocLoader.init(conf);
    ReloadListener confHolder = new ReloadListener();
    allocLoader.setReloadListener(confHolder);
    allocLoader.reloadAllocations();
    AllocationConfiguration queueConf = confHolder.allocConf;

    assertEquals(5, queueConf.getConfiguredQueues().get(FSQueueType.LEAF).size());
    assertEquals(Resources.createResource(0),
        queueConf.getMinResources("root." + YarnConfiguration.DEFAULT_QUEUE_NAME));
    assertEquals(Resources.createResource(0),
        queueConf.getMinResources("root." + YarnConfiguration.DEFAULT_QUEUE_NAME));

    assertEquals(Resources.createResource(1024, 0),
        queueConf.getMinResources("root.queueA"));
    assertEquals(Resources.createResource(2048, 0),
        queueConf.getMinResources("root.queueB"));
    assertEquals(Resources.createResource(0),
        queueConf.getMinResources("root.queueC"));
    assertEquals(Resources.createResource(0),
        queueConf.getMinResources("root.queueD"));
    assertEquals(Resources.createResource(0),
        queueConf.getMinResources("root.queueE"));

    assertEquals(15, queueConf.getQueueMaxApps("root." + YarnConfiguration.DEFAULT_QUEUE_NAME));
    assertEquals(15, queueConf.getQueueMaxApps("root.queueA"));
    assertEquals(15, queueConf.getQueueMaxApps("root.queueB"));
    assertEquals(15, queueConf.getQueueMaxApps("root.queueC"));
    assertEquals(3, queueConf.getQueueMaxApps("root.queueD"));
    assertEquals(15, queueConf.getQueueMaxApps("root.queueE"));
    assertEquals(10, queueConf.getUserMaxApps("user1"));
    assertEquals(5, queueConf.getUserMaxApps("user2"));

    // Unspecified queues should get default ACL
    assertEquals(" ", queueConf.getQueueAcl("root.queueA",
        QueueACL.ADMINISTER_QUEUE).getAclString());
    assertEquals(" ", queueConf.getQueueAcl("root.queueA",
        QueueACL.SUBMIT_APPLICATIONS).getAclString());

    // Queue B ACL
    assertEquals("alice,bob admins", queueConf.getQueueAcl("root.queueB",
        QueueACL.ADMINISTER_QUEUE).getAclString());

    // Queue C ACL
    assertEquals("alice,bob admins", queueConf.getQueueAcl("root.queueC",
        QueueACL.SUBMIT_APPLICATIONS).getAclString());

    assertEquals(120000, queueConf.getMinSharePreemptionTimeout("root"));
    assertEquals(-1, queueConf.getMinSharePreemptionTimeout("root." +
        YarnConfiguration.DEFAULT_QUEUE_NAME));
    assertEquals(-1, queueConf.getMinSharePreemptionTimeout("root.queueA"));
    assertEquals(-1, queueConf.getMinSharePreemptionTimeout("root.queueB"));
    assertEquals(-1, queueConf.getMinSharePreemptionTimeout("root.queueC"));
    assertEquals(-1, queueConf.getMinSharePreemptionTimeout("root.queueD"));
    assertEquals(60000, queueConf.getMinSharePreemptionTimeout("root.queueE"));

    assertEquals(300000, queueConf.getFairSharePreemptionTimeout("root"));
    assertEquals(-1, queueConf.getFairSharePreemptionTimeout("root." +
        YarnConfiguration.DEFAULT_QUEUE_NAME));
    assertEquals(-1, queueConf.getFairSharePreemptionTimeout("root.queueA"));
    assertEquals(-1, queueConf.getFairSharePreemptionTimeout("root.queueB"));
    assertEquals(-1, queueConf.getFairSharePreemptionTimeout("root.queueC"));
    assertEquals(-1, queueConf.getFairSharePreemptionTimeout("root.queueD"));
    assertEquals(-1, queueConf.getFairSharePreemptionTimeout("root.queueE"));

    assertEquals(.6f, queueConf.getFairSharePreemptionThreshold("root"), 0.01);
    assertEquals(-1, queueConf.getFairSharePreemptionThreshold("root."
        + YarnConfiguration.DEFAULT_QUEUE_NAME), 0.01);
    assertEquals(-1,
        queueConf.getFairSharePreemptionThreshold("root.queueA"), 0.01);
    assertEquals(-1,
        queueConf.getFairSharePreemptionThreshold("root.queueB"), 0.01);
    assertEquals(-1,
        queueConf.getFairSharePreemptionThreshold("root.queueC"), 0.01);
    assertEquals(-1,
        queueConf.getFairSharePreemptionThreshold("root.queueD"), 0.01);
    assertEquals(.3f,
        queueConf.getFairSharePreemptionThreshold("root.queueE"), 0.01);
  }
  
  @Test
  public void testSimplePlacementPolicyFromConf() throws Exception {
    Configuration conf = new Configuration();
    conf.set(FairSchedulerConfiguration.ALLOCATION_FILE, ALLOC_FILE);
    conf.setBoolean(FairSchedulerConfiguration.ALLOW_UNDECLARED_POOLS, false);
    conf.setBoolean(FairSchedulerConfiguration.USER_AS_DEFAULT_QUEUE, false);
    
    PrintWriter out = new PrintWriter(new FileWriter(ALLOC_FILE));
    out.println("<?xml version=\"1.0\"?>");
    out.println("<allocations>");
    out.println("</allocations>");
    out.close();
    
    AllocationFileLoaderService allocLoader = new AllocationFileLoaderService();
    allocLoader.init(conf);
    ReloadListener confHolder = new ReloadListener();
    allocLoader.setReloadListener(confHolder);
    allocLoader.reloadAllocations();
    AllocationConfiguration allocConf = confHolder.allocConf;
    
    QueuePlacementPolicy placementPolicy = allocConf.getPlacementPolicy();
    List<QueuePlacementRule> rules = placementPolicy.getRules();
    assertEquals(2, rules.size());
    assertEquals(QueuePlacementRule.Specified.class, rules.get(0).getClass());
    assertEquals(false, rules.get(0).create);
    assertEquals(QueuePlacementRule.Default.class, rules.get(1).getClass());
  }
  
  /**
   * Verify that you can't place queues at the same level as the root queue in
   * the allocations file.
   */
  @Test (expected = AllocationConfigurationException.class)
  public void testQueueAlongsideRoot() throws Exception {
    Configuration conf = new Configuration();
    conf.set(FairSchedulerConfiguration.ALLOCATION_FILE, ALLOC_FILE);

    PrintWriter out = new PrintWriter(new FileWriter(ALLOC_FILE));
    out.println("<?xml version=\"1.0\"?>");
    out.println("<allocations>");
    out.println("<queue name=\"root\">");
    out.println("</queue>");
    out.println("<queue name=\"other\">");
    out.println("</queue>");
    out.println("</allocations>");
    out.close();
    
    AllocationFileLoaderService allocLoader = new AllocationFileLoaderService();
    allocLoader.init(conf);
    ReloadListener confHolder = new ReloadListener();
    allocLoader.setReloadListener(confHolder);
    allocLoader.reloadAllocations();
  }

  /**
   * Verify that you can't include periods as the queue name in the allocations
   * file.
   */
  @Test (expected = AllocationConfigurationException.class)
  public void testQueueNameContainingPeriods() throws Exception {
    Configuration conf = new Configuration();
    conf.set(FairSchedulerConfiguration.ALLOCATION_FILE, ALLOC_FILE);

    PrintWriter out = new PrintWriter(new FileWriter(ALLOC_FILE));
    out.println("<?xml version=\"1.0\"?>");
    out.println("<allocations>");
    out.println("<queue name=\"parent1.child1\">");
    out.println("</queue>");
    out.println("</allocations>");
    out.close();

    AllocationFileLoaderService allocLoader = new AllocationFileLoaderService();
    allocLoader.init(conf);
    ReloadListener confHolder = new ReloadListener();
    allocLoader.setReloadListener(confHolder);
    allocLoader.reloadAllocations();
  }

  /**
   * Verify that you can't have the queue name with whitespace only in the
   * allocations file.
   */
  @Test (expected = AllocationConfigurationException.class)
  public void testQueueNameContainingOnlyWhitespace() throws Exception {
    Configuration conf = new Configuration();
    conf.set(FairSchedulerConfiguration.ALLOCATION_FILE, ALLOC_FILE);

    PrintWriter out = new PrintWriter(new FileWriter(ALLOC_FILE));
    out.println("<?xml version=\"1.0\"?>");
    out.println("<allocations>");
    out.println("<queue name=\"      \">");
    out.println("</queue>");
    out.println("</allocations>");
    out.close();

    AllocationFileLoaderService allocLoader = new AllocationFileLoaderService();
    allocLoader.init(conf);
    ReloadListener confHolder = new ReloadListener();
    allocLoader.setReloadListener(confHolder);
    allocLoader.reloadAllocations();
  }

  /**
   * Verify that you can't have the queue name with just a non breaking
   * whitespace in the allocations file.
   */
  @Test (expected = AllocationConfigurationException.class)
  public void testQueueNameContainingNBWhitespace() throws Exception {
    Configuration conf = new Configuration();
    conf.set(FairSchedulerConfiguration.ALLOCATION_FILE, ALLOC_FILE);

    PrintWriter out = new PrintWriter(new OutputStreamWriter(
        new FileOutputStream(ALLOC_FILE), StandardCharsets.UTF_8));
    out.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
    out.println("<allocations>");
    out.println("<queue name=\"\u00a0\">");
    out.println("</queue>");
    out.println("</allocations>");
    out.close();

    AllocationFileLoaderService allocLoader = new AllocationFileLoaderService();
    allocLoader.init(conf);
    ReloadListener confHolder = new ReloadListener();
    allocLoader.setReloadListener(confHolder);
    allocLoader.reloadAllocations();
  }

  /**
   * Verify that defaultQueueSchedulingMode can't accept FIFO as a value.
   */
  @Test (expected = AllocationConfigurationException.class)
  public void testDefaultQueueSchedulingModeIsFIFO() throws Exception {
    Configuration conf = new Configuration();
    conf.set(FairSchedulerConfiguration.ALLOCATION_FILE, ALLOC_FILE);

    PrintWriter out = new PrintWriter(new FileWriter(ALLOC_FILE));
    out.println("<?xml version=\"1.0\"?>");
    out.println("<allocations>");
    out.println("<defaultQueueSchedulingPolicy>fifo</defaultQueueSchedulingPolicy>");
    out.println("</allocations>");
    out.close();

    AllocationFileLoaderService allocLoader = new AllocationFileLoaderService();
    allocLoader.init(conf);
    ReloadListener confHolder = new ReloadListener();
    allocLoader.setReloadListener(confHolder);
    allocLoader.reloadAllocations();
  }

  private class ReloadListener implements AllocationFileLoaderService.Listener {
    public AllocationConfiguration allocConf;
    
    @Override
    public void onReload(AllocationConfiguration info) {
      allocConf = info;
    }
  }
}
