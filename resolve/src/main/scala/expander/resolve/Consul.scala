package expander.resolve

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.util.FastFuture.EnhancedFuture
import akka.stream.Materializer
import akka.util.ByteString
import play.api.libs.json.Json

import scala.concurrent.{ ExecutionContext, Future }

class Consul(
    val baseAddr:   String,
    val dnsEnabled: Boolean

)(implicit system: ActorSystem, mat: Materializer) {
  val http = Http(system)

  case class Value(
    CreateIndex: Int,
    ModifyIndex: Int,
    LockIndex:   Int,
    Key:         String,
    Flags:       Int,
    Value:       String,
    Session:     Option[String]
  )

  case class Session(
    LockDelay:   Long,
    Checks:      Set[String],
    Node:        String,
    ID:          String,
    CreateIndex: Int,
    Name:        Option[String]
  )

  case class Node(
    Node: NodeInfo
  )

  case class NodeInfo(
    Node:            String,
    Address:         String,
    TaggedAddresses: Map[String, String]
  )

  implicit val nodeNodeFmt = Json.format[NodeInfo]
  implicit val sessionFmt = Json.format[Session]
  implicit val valueFmt = Json.format[Value]
  implicit val nodeFmt = Json.format[Node]

  def getSessionInfo(id: String)(implicit ctx: ExecutionContext): Future[Seq[Session]] =
    http.singleRequest(HttpRequest(uri = baseAddr + "/v1/session/info/" + id))
      .flatMap(resp ⇒
        resp.entity.dataBytes
          .runFold(ByteString(""))(_ ++ _).fast
          .map(bs ⇒ Json.parse(bs.utf8String).asOpt[Seq[Session]].getOrElse(Seq.empty)))

  def getValue(key: String)(implicit ctx: ExecutionContext): Future[Seq[Value]] =
    http.singleRequest(HttpRequest(uri = baseAddr + "/v1/kv/" + key))
      .flatMap {
        case resp if resp.status.isSuccess() ⇒
          resp.entity.dataBytes
            .runFold(ByteString(""))(_ ++ _).fast
            .map(bs ⇒ Json.parse(bs.utf8String).asOpt[Seq[Value]].getOrElse(Seq.empty))
        case _ ⇒
          Future.successful(Seq.empty[Value])
      }

  def getNode(node: String)(implicit ctx: ExecutionContext): Future[Node] =
    http.singleRequest(HttpRequest(
      uri = baseAddr + "/v1/catalog/node/" + node
    ))
      .flatMap(resp ⇒
        resp.entity.dataBytes
          .runFold(ByteString(""))(_ ++ _).fast
          .map(bs ⇒ Json.parse(bs.utf8String).as[Node]))

  def createSession(flags: Int, name: String, ttl: Int = 20, checks: Set[String] = Set.empty)(implicit ctx: ExecutionContext): Future[String] =
    http.singleRequest(HttpRequest(
      method = HttpMethods.PUT,
      uri = baseAddr + "/v1/session/create",
      entity = HttpEntity.apply(ContentTypes.`application/json`, Json.stringify(Json.obj(
        "Name" → name,
        "Checks" → (checks + "serfHealth"),
        "Flags" → flags,
        "Behavior" → "delete",
        "TTL" → s"${ttl}s"
      )))
    )).flatMap(resp ⇒
      resp.entity.dataBytes
        .runFold(ByteString(""))(_ ++ _)).fast.map { r ⇒
      (Json.parse(r.utf8String) \ "ID").as[String]
    }

  def renewSession(id: String)(implicit ctx: ExecutionContext): Future[AnyRef] =
    http.singleRequest(HttpRequest(
      method = HttpMethods.PUT,
      uri = baseAddr + "/v1/session/renew/" + id
    ))

  def acquire(key: String, sesId: String)(implicit ctx: ExecutionContext): Future[Boolean] = {
    http.singleRequest(HttpRequest(
      method = HttpMethods.PUT,
      uri = s"$baseAddr/v1/kv/$key?acquire=$sesId"
    )).flatMap(resp ⇒
      resp.entity.dataBytes
        .runFold(ByteString(""))(_ ++ _)).fast.map(_.utf8String == "true")
  }

}
