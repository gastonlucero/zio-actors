package zio.actors

import java.io.{ IOException, ObjectInputStream, ObjectOutputStream, ObjectStreamException }

import zio.nio.core.{ InetAddress, InetSocketAddress, SocketAddress }
import zio.nio.core.channels.AsynchronousSocketChannel
import zio.{ DefaultRuntime, IO, Task, UIO }

/**
 *
 * Reference to actor that might reside on local JVM instance or be available via remote communication
 *
 * @tparam E error type
 * @tparam F wrapper type constructing DSL
 */
sealed trait ActorRef[+E <: Throwable, -F[+_]] extends Serializable {

  /**
   *
   * Send a message to an actor as `ask` interaction pattern -
   * caller is blocked until the response is received
   *
   * @param fa message
   * @tparam A return type
   * @return effectful response
   */
  def ?[A](fa: F[A]): Task[A]

  /**
   *
   * Send message to an actor as `fire-and-forget` -
   * caller is blocked until message is enqueued by recipient's mailbox
   *
   * @param fa message
   * @return lifted unit
   */
  def !(fa: F[_]): Task[Unit]

  /**
   * Get referential absolute actor path
   * @return
   */
  val path: UIO[String]

  /**
   * Not available from outside of the actor.
   * Actor can be stopped only within behavior's context or by ActorSystem shutdown
   */
  private[actors] val stop: Task[List[_]]
}

/* INTERNAL API */

private[actors] object ActorRefSerial {
  private[actors] val runtimeForResolve = new DefaultRuntime {}
}

private[actors] sealed abstract class ActorRefSerial[E <: Throwable, -F[+_]](private var actorPath: String)
    extends ActorRef[E, F]
    with Serializable {
  import ActorSystemUtils._

  @throws[IOException]
  protected def writeObject1(out: ObjectOutputStream): Unit =
    out.writeObject(actorPath)

  @throws[IOException]
  protected def readObject1(in: ObjectInputStream): Unit = {
    val rawActorPath = in.readObject()
    actorPath = rawActorPath.asInstanceOf[String]
  }

  @throws[ObjectStreamException]
  protected def readResolve1(): Object = {
    val remoteRef = for {
      resolved           <- resolvePath(actorPath)
      (_, addr, port, _) = resolved
      address <- InetAddress
                  .byName(addr.value)
                  .flatMap(iAddr => SocketAddress.inetSocketAddress(iAddr, port.value))
    } yield new ActorRefRemote[E, F](actorPath, address)

    ActorRefSerial.runtimeForResolve.unsafeRun(remoteRef)
  }

  override val path: UIO[String] = UIO.effectTotal(actorPath)
}

private[actors] final class ActorRefLocal[E <: Throwable, -F[+_]](
  private val actorName: String,
  actor: Actor[E, F]
) extends ActorRefSerial[E, F](actorName) {
  override def ?[A](fa: F[A]): Task[A] = actor ? fa

  override def !(fa: F[_]): Task[Unit] = actor ! fa

  override val stop: IO[Nothing, List[_]] = actor.stop

  @throws[IOException]
  private def writeObject(out: ObjectOutputStream): Unit =
    super.writeObject1(out)

  @throws[IOException]
  private def readObject(in: ObjectInputStream): Unit =
    super.readObject1(in)

  @throws[ObjectStreamException]
  private def readResolve(): Object =
    super.readResolve1()
}

private[actors] final class ActorRefRemote[E <: Throwable, -F[+_]](
  private val actorName: String,
  address: InetSocketAddress
) extends ActorRefSerial[E, F](actorName) {
  import ActorSystemUtils._

  override def ?[A](fa: F[A]): Task[A] = sendEnvelope(Command.Ask(fa))

  override def !(fa: F[_]): Task[Unit] = sendEnvelope[Unit](Command.Tell(fa))

  override val stop: Task[List[_]] = sendEnvelope(Command.Stop)

  private def sendEnvelope[A](command: Command): Task[A] =
    for {
      client <- AsynchronousSocketChannel()
      response <- for {
                   _         <- client.connect(address)
                   actorPath <- path
                   _         <- writeToWire(client, new Envelope(command, actorPath))
                   response  <- readFromWire(client)
                 } yield response.asInstanceOf[Either[E, A]]
      result <- IO.fromEither(response)
    } yield result

  @throws[IOException]
  private def writeObject(out: ObjectOutputStream): Unit =
    super.writeObject1(out)

  @throws[IOException]
  private def readObject(in: ObjectInputStream): Unit =
    super.readObject1(in)

  @throws[ObjectStreamException]
  private def readResolve(): Object =
    super.readResolve1()
}
