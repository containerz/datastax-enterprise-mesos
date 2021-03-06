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

import net.elodina.mesos.util.{Version, Repr}
import java.util
import java.util.concurrent.TimeUnit

import com.google.protobuf.ByteString
import org.apache.log4j._
import org.apache.mesos.Protos._
import org.apache.mesos.{MesosSchedulerDriver, SchedulerDriver}

import scala.collection.JavaConversions._
import scala.concurrent.duration.Duration
import scala.language.postfixOps

import scala.collection.mutable.ListBuffer
import java.util.{Collections, Date}

object Scheduler extends org.apache.mesos.Scheduler with Constraints[Node] {
  private[dse] val logger = Logger.getLogger(this.getClass)
  private var driver: SchedulerDriver = null

  val version = new Version("0.2.1.3")

  def start() {
    initLogging()
    logger.info(s"Starting scheduler:\n$Config")

    Config.resolveDeps()
    Nodes.namespace = Config.namespace
    Nodes.load()
    HttpServer.start()

    val frameworkBuilder = FrameworkInfo.newBuilder()
    frameworkBuilder.setUser(if (Config.user != null) Config.user else "")
    if (Nodes.frameworkId != null) frameworkBuilder.setId(FrameworkID.newBuilder().setValue(Nodes.frameworkId))
    frameworkBuilder.setRole(Config.frameworkRole)

    frameworkBuilder.setName(Config.frameworkName)
    frameworkBuilder.setFailoverTimeout(Config.frameworkTimeout.toUnit(TimeUnit.SECONDS))
    frameworkBuilder.setCheckpoint(true)

    var credsBuilder: Credential.Builder = null
    if (Config.principal != null && Config.secret != null) {
      frameworkBuilder.setPrincipal(Config.principal)

      credsBuilder = Credential.newBuilder()
        .setPrincipal(Config.principal)
        .setSecret(ByteString.copyFromUtf8(Config.secret))
    }

    val driver = if (credsBuilder == null) new MesosSchedulerDriver(this, frameworkBuilder.build, Config.master)
    else new MesosSchedulerDriver(this, frameworkBuilder.build, Config.master, credsBuilder.build())

    Runtime.getRuntime.addShutdownHook(new Thread() {
      override def run() {
        HttpServer.stop()
        if (Nodes.storage != null) Nodes.storage.close()
      }
    })

    val status = if (driver.run eq Status.DRIVER_STOPPED) 0 else 1
    sys.exit(status)
  }

  override def registered(driver: SchedulerDriver, id: FrameworkID, master: MasterInfo) {
    logger.info("[registered] framework:" + Repr.id(id.getValue) + " master:" + Repr.master(master))
    checkMesosVersion(master, driver)

    Nodes.frameworkId = id.getValue
    Nodes.save()

    this.driver = driver
    Reconciler.reconcileTasksIfRequired(force = true)
  }

  override def reregistered(driver: SchedulerDriver, master: MasterInfo) {
    logger.info("[reregistered] master:" + Repr.master(master))
    this.driver = driver
    Reconciler.reconcileTasksIfRequired(force = true)
  }

  override def offerRescinded(driver: SchedulerDriver, id: OfferID) {
    logger.info("[offerRescinded] " + Repr.id(id.getValue))
  }

  override def disconnected(driver: SchedulerDriver) {
    logger.info("[disconnected]")
    this.driver = null
  }

  override def slaveLost(driver: SchedulerDriver, id: SlaveID) {
    logger.info("[slaveLost] " + Repr.id(id.getValue))
  }

  override def error(driver: SchedulerDriver, message: String) {
    logger.info("[error] " + message)
  }

  override def statusUpdate(driver: SchedulerDriver, status: TaskStatus) {
    logger.info("[statusUpdate] " + Repr.status(status))
    onTaskStatus(status)
  }

  override def frameworkMessage(driver: SchedulerDriver, executorId: ExecutorID, slaveId: SlaveID, data: Array[Byte]) {
    logger.info("[frameworkMessage] executor:" + Repr.id(executorId.getValue) + " slave:" + Repr.id(slaveId.getValue) + " data: " + new String(data))
  }

  override def resourceOffers(driver: SchedulerDriver, offers: util.List[Offer]) {
    logger.debug("[resourceOffers]\n" + Repr.offers(offers))
    onOffers(offers.toList)
  }

  override def executorLost(driver: SchedulerDriver, executorId: ExecutorID, slaveId: SlaveID, status: Int) {
    logger.info("[executorLost] executor:" + Repr.id(executorId.getValue) + " slave:" + Repr.id(slaveId.getValue) + " status:" + status)
  }

  override def nodes: Traversable[Node] = Nodes.getNodes

  private def onOffers(offers: List[Offer]) {
    for (offer <- offers) {
      val declineReason = acceptOffer(offer)

      if (declineReason != null) {
        driver.declineOffer(offer.getId)
        logger.info(s"Declined offer ${Repr.offer(offer)}:\n  $declineReason")
      }
    }

    Reconciler.reconcileTasksIfRequired()
    Nodes.save()
  }

  private[dse] def acceptOffer(offer: Offer, now: Date = new Date()): String = {
    if (Reconciler.isReconciling) return "reconciling"

    val nodes: List[Node] = Nodes.getNodes.filter(_.state == Node.State.STARTING).filterNot(_.failover.isWaitingDelay(now))
    if (nodes.isEmpty) return "no nodes to start"

    val starting = nodes.find(_.runtime != null).getOrElse(null)
    if (starting != null) return s"node ${starting.id} is starting"

    val reasons = new ListBuffer[String]()
    for (node <- nodes.sortBy(!_.seed)) {
      var reason = node.matches(offer, now)
      if (reason == null) reason = checkSeedConstraints(offer, node).getOrElse(null)
      if (reason == null) reason = checkConstraints(offer, node).getOrElse(null)

      if (reason != null) reasons += s"node ${node.id}: $reason"
      else {
        launchTask(node, offer)
        return null
      }
    }

    reasons.mkString(", ")
  }

  private def checkSeedConstraints(offer: Offer, node: Node): Option[String] = {
    if (node.seed) checkConstraintsWith(offer, node, _.seedConstraints, name => nodes.filter(_.seed).flatMap(_.attribute(name)).toList)
    else None
  }

  private def launchTask(node: Node, offer: Offer) {
    node.runtime = new Node.Runtime(node, offer)
    val task = node.newTask()
    node.modified = false

    driver.launchTasks(util.Arrays.asList(offer.getId), util.Arrays.asList(task), Filters.newBuilder().setRefuseSeconds(1).build)
    logger.info(s"Starting node ${node.id} with task ${node.runtime.taskId} for offer ${offer.getId.getValue}")
  }

  private[dse] def onTaskStatus(status: TaskStatus) {
    val node = Nodes.getNode(Node.idFromTaskId(status.getTaskId.getValue))

    status.getState match {
      case TaskState.TASK_STAGING | TaskState.TASK_STARTING =>
      case TaskState.TASK_RUNNING => onTaskStarted(node, status)
      case TaskState.TASK_LOST | TaskState.TASK_FAILED | TaskState.TASK_ERROR |
           TaskState.TASK_FINISHED | TaskState.TASK_KILLED => onTaskStopped(node, status)
      case _ => logger.warn("Got unexpected node state: " + status.getState)
    }

    Nodes.save()
  }

  private[dse] def onTaskStarted(node: Node, status: TaskStatus) {
    val sameTask = node != null && node.runtime != null && node.runtime.taskId == status.getTaskId.getValue
    val expectedState = node != null && List(Node.State.STARTING, Node.State.RUNNING, Node.State.RECONCILING).contains(node.state)

    if (!sameTask || !expectedState) {
      val id = if (node != null) s"${node.id}:${node.state}" else "<unknown>"
      logger.info(s"Got ${status.getState} for node $id, killing task ${status.getTaskId.getValue}")
      driver.killTask(status.getTaskId)
      return
    }

    if (node.state == Node.State.RECONCILING)
      logger.info(s"Finished reconciling of node ${node.id}, task ${node.runtime.taskId}")
    else
      node.runtime.address = status.getData.toStringUtf8

    node.state = Node.State.RUNNING

    // cassandra.replace_address is a one-time option
    if (node.cassandraJvmOptions != null)
      node.cassandraJvmOptions = node.cassandraJvmOptions.split(" ").filterNot(s => s.contains("-Dcassandra.replace_address")).mkString(" ")

    node.registerStart(node.runtime.hostname)
  }

  private[dse] def onTaskStopped(node: Node, status: TaskStatus, now: Date = new Date()) {
    val sameTask = node != null && node.runtime != null && node.runtime.taskId == status.getTaskId.getValue
    val expectedState = node != null && node.state != Node.State.IDLE

    if (!sameTask || !expectedState) {
      val id = if (node != null) s"${node.id}:${node.state}" else "<unknown>"
      logger.info(s"Got ${status.getState} for node $id, ignoring it")
      return
    }

    if (node.state == Node.State.RECONCILING)
      logger.info(s"Finished reconciling of node ${node.id}, task ${node.runtime.taskId}")

    val failed = node.state != Node.State.STOPPING && status.getState != TaskState.TASK_FINISHED && status.getState != TaskState.TASK_KILLED
    node.registerStop(now, failed)

    if (failed) {
      var msg = s"Node ${node.id} failed ${node.failover.failures}"
      if (node.failover.maxTries != null) msg += "/" + node.failover.maxTries

      if (!node.failover.isMaxTriesExceeded) {
        msg += ", waiting " + node.failover.currentDelay
        msg += ", next start ~ " + Repr.dateTime(node.failover.delayExpires)
      } else {
        node.state = Node.State.STOPPING
        msg += ", failure limit exceeded"
        msg += ", stopping node"
      }

      logger.info(msg)
    }

    node.state = if (node.state == Node.State.STOPPING) Node.State.IDLE else Node.State.STARTING
    node.runtime = null
    node.modified = false
  }

  def stopNode(id: String, force: Boolean = false): Unit = {
    val node = Nodes.getNode(id)
    if (node == null) return

    if (node.runtime == null) {
      node.failover.resetFailures()
      node.state = Node.State.IDLE
      return
    }

    if (driver == null) throw new IllegalStateException("scheduler disconnected from the master")
    logger.info(s"Killing task ${node.runtime.taskId} of node ${node.id}${if (force) " forcibly" else ""}")

    val executorId = ExecutorID.newBuilder().setValue(node.runtime.executorId).build()
    val slaveId = SlaveID.newBuilder().setValue(node.runtime.slaveId).build()
    val taskId = TaskID.newBuilder().setValue(node.runtime.taskId).build

    if (force) driver.sendFrameworkMessage(executorId, slaveId, "stop".getBytes)
    else driver.killTask(taskId)

    node.state = Node.State.STOPPING
  }

  private def checkMesosVersion(master: MasterInfo, driver: SchedulerDriver): Unit = {
    val minVersion = "0.23.0"
    val version = master.getVersion

    def versionNumber(ver: String): Int = {
      val parts = ver.split('.')
      parts(0).toInt * 1000000 + parts(1).toInt * 1000 + parts(2).toInt
    }

    if (version.isEmpty || versionNumber(version) < versionNumber(minVersion)) {
      val versionStr = if (version.isEmpty) "< \"0.23.0\"" else "\"" + version + "\""
      logger.fatal(s"""Minimum supported Mesos version is "$minVersion", whereas current version is $versionStr. Stopping Scheduler""")
      driver.stop
    }
  }

  private[dse] def initLogging() {
    System.setProperty("org.eclipse.jetty.util.log.class", classOf[JettyLog4jLogger].getName)

    BasicConfigurator.resetConfiguration()

    val root = Logger.getRootLogger
    root.setLevel(Level.INFO)

    Logger.getLogger("org.I0Itec.zkclient").setLevel(Level.WARN)
    Logger.getLogger("org.apache.zookeeper").setLevel(Level.WARN)

    val logger = Logger.getLogger(Scheduler.getClass)
    logger.setLevel(if (Config.debug) Level.DEBUG else Level.INFO)

    val layout = new PatternLayout("%d [%t] %-5p %c %x - %m%n")
    val appender = new ConsoleAppender(layout)
    root.addAppender(appender)
  }

  class JettyLog4jLogger extends org.eclipse.jetty.util.log.Logger {
    private var logger: Logger = Logger.getLogger("Jetty")

    def this(logger: Logger) {
      this()
      this.logger = logger
    }

    def isDebugEnabled: Boolean = logger.isDebugEnabled

    def setDebugEnabled(enabled: Boolean) = logger.setLevel(if (enabled) Level.DEBUG else Level.INFO)

    def getName: String = logger.getName

    def getLogger(name: String): org.eclipse.jetty.util.log.Logger = new JettyLog4jLogger(Logger.getLogger(name))

    def info(s: String, args: AnyRef*) = logger.info(format(s, args))

    def info(s: String, t: Throwable) = logger.info(s, t)

    def info(t: Throwable) = logger.info("", t)

    def debug(s: String, args: AnyRef*) = logger.debug(format(s, args))

    def debug(s: String, t: Throwable) = logger.debug(s, t)

    def debug(t: Throwable) = logger.debug("", t)

    def warn(s: String, args: AnyRef*) = logger.warn(format(s, args))

    def warn(s: String, t: Throwable) = logger.warn(s, t)

    def warn(s: String) = logger.warn(s)

    def warn(t: Throwable) = logger.warn("", t)

    def ignore(t: Throwable) = logger.info("Ignored", t)
  }

  private def format(s: String, args: AnyRef*): String = {
    var result: String = ""
    var i: Int = 0

    for (token <- s.split("\\{\\}")) {
      result += token
      if (args.length > i) result += args(i)
      i += 1
    }

    result
  }
  
  object Reconciler {
    private[dse] val RECONCILE_DELAY = Duration("20s")
    private[dse] val RECONCILE_MAX_TRIES = 3

    private[dse] var reconciles: Int = 0
    private[dse] var reconcileTime: Date = null

    private[dse] def isReconciling: Boolean = Nodes.getNodes.exists(_.state == Node.State.RECONCILING)

    private[dse] def reconcileTasksIfRequired(force: Boolean = false, now: Date = new Date()): Unit = {
      if (reconcileTime != null && now.getTime - reconcileTime.getTime < RECONCILE_DELAY.toMillis)
        return

      if (!isReconciling) reconciles = 0
      reconciles += 1
      reconcileTime = now

      if (reconciles > RECONCILE_MAX_TRIES) {
        for (node <- Nodes.getNodes.filter(n => n.runtime != null && n.state == Node.State.RECONCILING)) {
          logger.info(s"Reconciling exceeded $RECONCILE_MAX_TRIES tries for node ${node.id}, sending killTask for task ${node.runtime.taskId}")
          driver.killTask(TaskID.newBuilder().setValue(node.runtime.taskId).build())
          node.runtime = null
        }

        return
      }

      val statuses = new util.ArrayList[TaskStatus]

      for (node <- Nodes.getNodes.filter(_.runtime != null))
        if (force || node.state == Node.State.RECONCILING) {
          node.state = Node.State.RECONCILING
          logger.info(s"Reconciling $reconciles/$RECONCILE_MAX_TRIES state of node ${node.id}, task ${node.runtime.taskId}")

          statuses.add(TaskStatus.newBuilder()
            .setTaskId(TaskID.newBuilder().setValue(node.runtime.taskId))
            .setState(TaskState.TASK_STAGING)
            .build()
          )
        }

      if (force || !statuses.isEmpty)
        driver.reconcileTasks(if (force) Collections.emptyList() else statuses)
    }
  }
}
