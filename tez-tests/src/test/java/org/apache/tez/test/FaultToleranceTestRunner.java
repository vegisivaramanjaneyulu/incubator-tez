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

package org.apache.tez.test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Random;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.yarn.api.records.LocalResource;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.tez.client.AMConfiguration;
import org.apache.tez.client.TezClientUtils;
import org.apache.tez.client.TezSession;
import org.apache.tez.client.TezSessionConfiguration;
import org.apache.tez.client.TezSessionStatus;
import org.apache.tez.dag.api.DAG;
import org.apache.tez.dag.api.TezConfiguration;
import org.apache.tez.dag.api.TezUncheckedException;
import org.apache.tez.dag.api.client.DAGClient;
import org.apache.tez.dag.api.client.DAGStatus;

/**
 * Run a DAG on a cluster with the given configuration. Starts a TezSession
 * using default cluster configuration from installation. Then uses reflection
 * to get the class from the first class-name argument. That class must have a
 * static method - createDAG(org.apache.hadoop.conf.Configuration) that returns
 * a DAG. Configuration is picked up by reading the file specified via the
 * second path argument. The static method is invoked to get the DAG. The DAG is
 * then executed in the session. Returns success if DAG succeeds.
 */
public class FaultToleranceTestRunner {
  
  static String TEST_ROOT_DIR = "tmp";
  
  TezSession tezSession = null;
  Resource defaultResource = Resource.newInstance(100, 0);
  
  
  void setup() throws Exception {
    TezConfiguration tezConf = new TezConfiguration(new YarnConfiguration());
    FileSystem defaultFs = FileSystem.get(tezConf);
    
    Path remoteStagingDir = defaultFs.makeQualified(new Path(TEST_ROOT_DIR, String
        .valueOf(new Random().nextInt(100000))));
    TezClientUtils.ensureStagingDirExists(tezConf, remoteStagingDir);
    
    tezConf.set(TezConfiguration.TEZ_AM_STAGING_DIR,
        remoteStagingDir.toString());

    AMConfiguration amConfig = new AMConfiguration(
        new HashMap<String, String>(), new HashMap<String, LocalResource>(),
        tezConf, null);
    TezSessionConfiguration tezSessionConfig =
        new TezSessionConfiguration(amConfig, tezConf);
    tezSession = new TezSession("FaultToleranceTestRunner", tezSessionConfig);
    tezSession.start();
  }
  
  void tearDown() throws Exception {
    if (tezSession != null) {
      tezSession.stop();
    }
  }
  
  DAG getDAG(String className, String confFilePath) throws Exception {
    Class<?> clazz = Class.forName(className);
    Method method = clazz.getMethod("createDAG", Configuration.class);
    
    Configuration testConf = new Configuration(false);
    if (confFilePath != null) {
      Path confPath = new Path(confFilePath);
      testConf.addResource(confPath);
    }
    
    DAG dag = (DAG) method.invoke(null, testConf);
    
    return dag;
  }
  
  boolean run(String className, String confFilePath) throws Exception {
    setup();
    
    try {
      TezSessionStatus status = tezSession.getSessionStatus();
      while (status != TezSessionStatus.READY && status != TezSessionStatus.SHUTDOWN) {
        System.out.println("Waiting for session to be ready. Current: " + status);
        Thread.sleep(500);
        status = tezSession.getSessionStatus();
      }
      if (status == TezSessionStatus.SHUTDOWN) {
        throw new TezUncheckedException("Unexpected Session shutdown");
      }
      
      DAG dag = getDAG(className, confFilePath);
      
      DAGClient dagClient = tezSession.submitDAG(dag);
      DAGStatus dagStatus = dagClient.getDAGStatus(null);
      while (!dagStatus.isCompleted()) {
        System.out.println("Waiting for dag to complete. Sleeping for 500ms."
            + " DAG name: " + dag.getName()
            + " DAG appId: " + dagClient.getApplicationId()
            + " Current state: " + dagStatus.getState());
        Thread.sleep(500);
        dagStatus = dagClient.getDAGStatus(null);
      }
      
      if (dagStatus.getState() == DAGStatus.State.SUCCEEDED) {
        return true;
      }
      
    } finally {
      tearDown();
    }
    
    return false;
  }
  
  static void printUsage() {
    System.err.println(
        "Usage: " + " FaultToleranceTestRunner <dag-class-name> <test-conf-path>");
  }
  
  public static void main(String[] args) throws Exception {
    String className = null;
    String confFilePath = null;
    if (args.length == 1) {
      className = args[0];
    } else if (args.length == 2) {
      className = args[0];
      confFilePath = args[1];
    } else {
      printUsage();
      System.exit(1);
    }
    
    FaultToleranceTestRunner job = new FaultToleranceTestRunner();
    if (job.run(className, confFilePath)) {
      System.out.println("Succeeded.");
    } else {
      System.out.println("Failed.");
      System.exit(2);
    } 
  }
}
