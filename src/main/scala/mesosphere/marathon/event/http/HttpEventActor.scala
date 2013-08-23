package mesosphere.marathon.event.http

import akka.actor._
import spray.http._
import spray.client.pipelining._

import spray.client.pipelining.sendReceive
import scala.concurrent.Future
import spray.httpx.{ Json4sJacksonSupport}
import org.json4s.{DefaultFormats, FieldSerializer, NoTypeHints}
import spray.client.pipelining._
import spray.http.HttpRequest
import spray.http.HttpResponse
import scala.util.Success
import scala.util.Failure
import mesosphere.marathon.event.{ApiPostEvent, MesosStatusUpdateEvent, MarathonEvent}
import mesosphere.marathon.api.v1.AppDefinition
import org.apache.mesos.Protos.{TaskID, TaskStatus, TaskState}
import mesosphere.marathon.Main
import com.google.inject.Provides
import com.google.inject.name.Named
import spray.http.HttpRequest
import spray.http.HttpResponse
import scala.util.Success
import scala.util.Failure

class HttpEventActor extends Actor with ActorLogging with Json4sJacksonSupport {

  implicit val ec = HttpEventModule.executionContext

  val urls = Main.getConfiguration.httpEventEndpoints()

  val pipeline: HttpRequest => Future[HttpResponse] = (
    addHeader("Accept", "application/json")
      ~> sendReceive)

  def receive = {
    case event: MarathonEvent => {
      log.info("POSTing to all endpoints.")
      urls.foreach(x => post(x, event))
    }
    case _ => {
      log.warning("Message not understood!")
    }
  }

  def post(urlString: String, event: MarathonEvent) {
    log.info("Sending POST to:" + urlString)
    val request = Post(urlString, event)
    val response = pipeline(request)

    response.onComplete {
      case Success(res) => {
        if (res.status.isFailure) {
          log.warning(s"Failed to post $event")
        }
      }
      case Failure(t) => {
        log.warning(s"Failed to post $event")
      }
    }
  }

  implicit def json4sJacksonFormats = DefaultFormats + FieldSerializer
    [AppDefinition]()

}

//case class Foo(val url: String, val e: ApiPostEvent)
//
//object HttpEventActor {
//
//
//  def main(arg: Array[String]) {
//
//    val system =  ActorSystem("MarathonEvents")
//    val foo = system.actorOf(Props[HttpEventActor])
//
//    val appDef = new AppDefinition
//    appDef.cmd = "bash -x /tmp/blah"
//    appDef.cpus = 1.5
//    appDef.mem = 1024
//    appDef.id = "stuff"
//    appDef.instances = 5
//    appDef.rackAffinity = 1.0
//    appDef.nodeAffinity = 0.0
//    appDef.env = Map("HADOOP_HOME" -> "/tmp/hadoop")
//    appDef.uris = Seq("file:///tmp/pckg1.tgz", "file:///tmp/pckg2.tgz")
//    foo ! new Foo("http://localhost:1500/",
//      new ApiPostEvent("1.1.1.1", "/v1/app/scale", appDef))
//  }
//}
















