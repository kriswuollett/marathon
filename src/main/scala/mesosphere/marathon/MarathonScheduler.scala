package mesosphere.marathon

import org.apache.mesos.Protos._
import org.apache.mesos.{SchedulerDriver, Scheduler}
import java.util.logging.{Level, Logger}
import scala.collection.JavaConverters._
import mesosphere.mesos.TaskBuilder
import mesosphere.marathon.api.v1.AppDefinition
import mesosphere.marathon.state.MarathonStore
import scala.util.{Failure, Success}
import scala.concurrent.ExecutionContext
import com.google.common.collect.Lists
import javax.inject.{Named, Inject}
import com.google.common.eventbus.EventBus
import mesosphere.marathon.event.{EventModule, MesosStatusUpdateEvent}


/**
 * @author Tobi Knaup
 */
class MarathonScheduler @Inject()(
    @Named(EventModule.busName) eventBus: Option[EventBus],
    store: MarathonStore[AppDefinition],
    taskTracker: TaskTracker,
    taskQueue: TaskQueue)
  extends Scheduler {

  val log = Logger.getLogger(getClass.getName)

  // TODO use a thread pool here
  import ExecutionContext.Implicits.global

  def registered(driver: SchedulerDriver, frameworkId: FrameworkID, master: MasterInfo) {
    log.info("Registered as %s to master '%s'".format(frameworkId.getValue, master.getId))
  }

  def reregistered(driver: SchedulerDriver, master: MasterInfo) {
    log.info("Re-registered to %s".format(master))
  }

  def resourceOffers(driver: SchedulerDriver, offers: java.util.List[Offer]) {
    for (offer <- offers.asScala) {
      log.info("Received offer %s".format(offer))

      // TODO launch multiple tasks if the offer is big enough
      val app = taskQueue.poll()

      if (app != null) {
        newTask(app, offer) match {
          case Some(task) => {
            val taskInfos = Lists.newArrayList(task)
            log.fine("Launching tasks: " + taskInfos)

            val port = TaskBuilder.getPort(offer).get
            val marathonTask = MarathonTask(task.getTaskId.getValue, offer.getHostname, port)
            taskTracker.starting(app.id, marathonTask)
            driver.launchTasks(offer.getId, taskInfos)
          }
          case None => {
            log.fine("Offer doesn't match request. Declining.")
            // Add it back into the queue so the we can try again
            taskQueue.add(app)
            driver.declineOffer(offer.getId)
          }
        }
      } else {
        log.fine("Task queue is empty. Declining offer.")
        driver.declineOffer(offer.getId)
      }
    }
  }

  def offerRescinded(driver: SchedulerDriver, offer: OfferID) {
    log.info("Offer %s rescinded".format(offer))
  }

  def statusUpdate(driver: SchedulerDriver, status: TaskStatus) {
    log.info("Received status update for task %s: %s (%s)"
      .format(status.getTaskId.getValue, status.getState, status.getMessage))

    val appID = TaskIDUtil.appID(status.getTaskId)

    if (eventBus.nonEmpty) {
      log.info("Sending event notification.")
      eventBus.get.post(new MesosStatusUpdateEvent(status.getTaskId.getValue,
        status.getState.getNumber))
    }

    if (status.getState.eq(TaskState.TASK_FAILED)
      || status.getState.eq(TaskState.TASK_FINISHED)
      || status.getState.eq(TaskState.TASK_KILLED)
      || status.getState.eq(TaskState.TASK_LOST)) {

      // Remove from our internal list
      taskTracker.terminated(appID, status.getTaskId)
      scale(driver, appID)
    } else if (status.getState.eq(TaskState.TASK_RUNNING)) {
      taskTracker.running(appID, status.getTaskId)
    }
  }

  def frameworkMessage(driver: SchedulerDriver, executor: ExecutorID, slave: SlaveID, message: Array[Byte]) {
    log.info("Received framework message %s %s %s ".format(executor, slave, message))
  }

  def disconnected(driver: SchedulerDriver) {
    log.warning("Disconnected")
    suicide()
  }

  def slaveLost(driver: SchedulerDriver, slave: SlaveID) {
    log.info("Lost slave %s".format(slave))
  }

  def executorLost(driver: SchedulerDriver, executor: ExecutorID, slave: SlaveID, p4: Int) {
    log.info("Lost executor %s %s %s ".format(executor, slave, p4))
  }

  def error(driver: SchedulerDriver, message: String) {
    log.warning("Error: %s".format(message))
    suicide()
  }

  // TODO move stuff below out of the scheduler

  def startApp(driver: SchedulerDriver, app: AppDefinition) {
    store.fetch(app.id).onComplete {
      case Success(option) => if (option.isEmpty) {
        store.store(app.id, app)
        log.info("Starting app " + app.id)
        taskTracker.startUp(app.id)
        scale(driver, app)
      } else {
        log.warning("Already started app " + app.id)
      }
      case Failure(t) =>
        log.log(Level.WARNING, "Failed to start app %s".format(app.id), t)
    }
  }

  def stopApp(driver: SchedulerDriver, app: AppDefinition) {
    store.expunge(app.id).onComplete {
      case Success(_) =>
        log.info("Stopping app " + app.id)
        val tasks = taskTracker.get(app.id)

        for (task <- tasks) {
          log.info("Killing task " + task.id)
          driver.killTask(TaskID.newBuilder.setValue(task.id).build)
        }
        taskTracker.shutDown(app.id)
        // TODO after all tasks have been killed we should remove the app from taskTracker
      case Failure(t) =>
        log.warning("Error stopping app %s: %s".format(app.id, t.getMessage))
    }
  }

  def scaleApp(driver: SchedulerDriver, app: AppDefinition) {
    store.fetch(app.id).onComplete {
      case Success(appOption) => {
        appOption match {
          case Some(storedApp) => {
            storedApp.instances = app.instances
            store.store(app.id, storedApp)
            scale(driver, storedApp)
          }
          case None =>
            log.warning("Service unknown: %s".format(app.id))
        }
      }
      case Failure(t) =>
        log.warning("Error scaling app %s: %s".format(app.id, t.getMessage))
    }
  }

  /**
   * Make sure all apps are running the configured amount of tasks.
   *
   * Should be called some time after the framework re-registers,
   * to give Mesos enough time to deliver task updates.
   * @param driver scheduler driver
   */
  def balanceTasks(driver: SchedulerDriver) {
    store.names().onComplete {
      case Success(iterator) => {
        log.info("Syncing tasks for all apps")
        for (appName <- iterator) {
          scale(driver, appName)
        }
      }
      case Failure(t) => {
        log.log(Level.WARNING, "Failed to get task names", t)
      }
    }
  }

  private def newTask(app: AppDefinition, offer: Offer): Option[TaskInfo] = {
    // TODO this should return a MarathonTask
    new TaskBuilder(app, taskTracker.newTaskId).buildIfMatches(offer)
  }

  /**
   * Make sure the app is running the correct number of instances
   * @param driver
   * @param app
   */
  private def scale(driver: SchedulerDriver, app: AppDefinition) {
    taskTracker.get(app.id).synchronized {
      val currentCount = taskTracker.count(app.id)
      val targetCount = app.instances

      if (targetCount > currentCount) {
        log.info("Need to scale %s from %d up to %d instances".format(app.id, currentCount, targetCount))

        val queuedCount = taskQueue.count(app)

        if ((currentCount + queuedCount) < targetCount) {
          for (i <- (currentCount + queuedCount) until targetCount) {
            log.info("Queueing task for %s".format(app.id))
            taskQueue.add(app)
          }
        } else {
          log.info("Already queued %d tasks for %s. Not scaling.".format(queuedCount, app.id))
        }
      }
      else if (targetCount < currentCount) {
        log.info("Scaling %s from %d down to %d instances".format(app.id, currentCount, targetCount))

        val kill = taskTracker.drop(app.id, targetCount)
        for (task <- kill) {
          log.info("Killing task " + task.id)
          driver.killTask(TaskID.newBuilder.setValue(task.id).build)
        }
      }
      else {
        log.info("Already running %d instances. Not scaling.".format(app.instances))
      }
    }
  }

  private def scale(driver: SchedulerDriver, appName: String) {
    store.fetch(appName).onSuccess {
      case Some(app) => scale(driver, app)
      case None => log.warning("App %s does not exist. Not scaling.".format(appName))
    }
  }

  private def suicide() {
    log.severe("Committing suicide")
    sys.exit(9)
  }
}
