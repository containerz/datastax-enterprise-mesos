/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.elodina.mesos.dse

import java.io.{File, PrintWriter, StringWriter}
import net.elodina.mesos.util.{IO, Repr}

import org.apache.log4j._
import org.apache.mesos.Protos._
import org.apache.mesos.{ExecutorDriver, MesosExecutorDriver}

import com.google.protobuf.ByteString
import Util.BindAddress

object Executor extends org.apache.mesos.Executor {
  private val logger = Logger.getLogger(Executor.getClass)
  private[dse] var dir: File = new File(".")

  private var hostname: String = null
  private var cassandraProcess: CassandraProcess = null
  private var agentProcess: AgentProcess = null

  def main(args: Array[String]) {
    initLogging()
    resolveDeps()

    val driver = new MesosExecutorDriver(Executor)
    val status = if (driver.run eq Status.DRIVER_STOPPED) 0 else 1

    sys.exit(status)
  }

  def registered(driver: ExecutorDriver, executor: ExecutorInfo, framework: FrameworkInfo, slave: SlaveInfo) {
    logger.info("[registered] framework:" + Repr.framework(framework) + " slave:" + Repr.slave(slave))

    this.hostname = slave.getHostname
  }

  def reregistered(driver: ExecutorDriver, slave: SlaveInfo) {
    logger.info("[reregistered] " + Repr.slave(slave))

    this.hostname = slave.getHostname
  }

  def disconnected(driver: ExecutorDriver) {
    logger.info("[disconnected]")
  }

  def launchTask(driver: ExecutorDriver, task: TaskInfo) {
    logger.info("[launchTask] " + Repr.task(task))
    driver.sendStatusUpdate(TaskStatus.newBuilder().setTaskId(task.getTaskId).setState(TaskState.TASK_STARTING).build)

    new Thread {
      override def run() {
        setName("ProcessWatcher")

        try {
          startCassandraAndWait(task, driver)
        } catch {
          case t: Throwable =>
            t.printStackTrace()

            val buffer = new StringWriter()
            t.printStackTrace(new PrintWriter(buffer, true))
            driver.sendStatusUpdate(TaskStatus.newBuilder().setTaskId(task.getTaskId).setState(TaskState.TASK_FAILED).setMessage("" + buffer).build)
        }

        stopProcesses()
        driver.stop()
      }
    }.start()
  }
  
  private def startCassandraAndWait(task: TaskInfo, driver: ExecutorDriver) {
    val json: Map[String, Any] = Util.parseJsonAsMap(task.getData.toStringUtf8)
    val node: Node = new Node(json, expanded = true)

    var env = Map[String, String]()
    if (Executor.jreDir != null) env += "JAVA_HOME" -> Executor.jreDir.getAbsolutePath

    val address = resolveAddress(node)

    cassandraProcess = CassandraProcess(node, task, address, env)
    cassandraProcess.start()

    if (dseDir != null) {
      agentProcess = AgentProcess(node, address, env)
      agentProcess.start()
    }

    if (cassandraProcess.awaitNormalState()) driver.sendStatusUpdate(TaskStatus.newBuilder().setTaskId(task.getTaskId).setData(ByteString.copyFromUtf8(address)).setState(TaskState.TASK_RUNNING).build)

    val error = cassandraProcess.await()
    if (error == null) driver.sendStatusUpdate(TaskStatus.newBuilder().setTaskId(task.getTaskId).setState(TaskState.TASK_FINISHED).build)
    else driver.sendStatusUpdate(TaskStatus.newBuilder().setTaskId(task.getTaskId).setState(TaskState.TASK_FAILED).setMessage(error).build)
  }

  def killTask(driver: ExecutorDriver, id: TaskID) {
    logger.info("[killTask] " + id.getValue)
    stopProcesses()
  }

  def frameworkMessage(driver: ExecutorDriver, data: Array[Byte]) {
    val message = new String(data)
    logger.info(s"[frameworkMessage] $message")

    if (message == "stop") driver.stop()
  }

  def shutdown(driver: ExecutorDriver) {
    logger.info("[shutdown]")
    stopProcesses()
  }

  def error(driver: ExecutorDriver, message: String) {
    logger.info("[error] " + message)
  }

  private def resolveAddress(node: Node): String = {
    val bindAddress: BindAddress = node.cluster.bindAddress
    if (bindAddress == null) return hostname

    val port = node.runtime.reservation.ports(Node.Port.STORAGE)
    val address = bindAddress.resolve(port)

    if (address == null) throw new IllegalStateException(s"Failed to resolve address $bindAddress or allocate port $port")
    address
  }

  private def stopProcesses() {
    if (cassandraProcess != null) cassandraProcess.stop()
    if (agentProcess != null) agentProcess.stop()
  }

  private def initLogging() {
    BasicConfigurator.resetConfiguration()

    val root = Logger.getRootLogger
    root.setLevel(Level.INFO)

    val logger = Logger.getLogger(Executor.getClass.getPackage.getName)
    logger.setLevel(if (System.getProperty("debug") != null) Level.DEBUG else Level.INFO)

    val layout = new PatternLayout("%d [%t] %-5p %c %x - %m%n")
    root.addAppender(new ConsoleAppender(layout))
  }

  var cassandraDir: File = null
  var dseDir: File = null
  var jreDir: File = null

  def resolveDeps() {
    cassandraDir = IO.findDir(dir, "apache-cassandra.*")
    dseDir = IO.findDir(dir, "dse.*")
    jreDir = IO.findDir(dir, "jre.*")

    if (dseDir == null && cassandraDir == null)
      throw new IllegalStateException("Either cassandra or dse dir should exist")
  }

  def cassandraConfDir: File = {
    if (dseDir != null) new File(dseDir, "resources/cassandra/conf") else new File(cassandraDir, "conf")
  }
}
