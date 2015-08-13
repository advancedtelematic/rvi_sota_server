package org.genivi.sota.core.rvi

import akka.testkit.TestFSMRef
import java.io.{File, FileOutputStream}

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.http.scaladsl.model.HttpResponse
import akka.stream.ActorMaterializer
import akka.testkit.{ImplicitSender, TestKit}
import java.net.URL
import org.genivi.sota.core.data.Vehicle
import org.scalatest._
import org.genivi.sota.core.data.Package
import org.genivi.sota.core.rvi._
import scala.concurrent.ExecutionContext

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

class TransferSpec(_system: ActorSystem) extends TestKit(_system) with ImplicitSender
  with WordSpecLike with Matchers with BeforeAndAfterAll {

  import Transfer._
  import Transfer.Actor._
  import Transfer.Commands._

  type TransferActorRef = TestFSMRef[State, Data, Transfer]

  def this() = this(ActorSystem("TransferSpec"))

  implicit val mat = ActorMaterializer()

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  sealed trait Message
  case class TransferStart(transactionId: Long, destination: String, packageIdentifier: String, byteSize: Long, checksum: String) extends Message
  case class TransferChunk(transactionId: Long, destination: String, index: Long, msg: String) extends Message
  case class TransferFinish(transactionId: Long) extends Message

  trait Acknowledgement {
    var ref: Option[ActorRef] = None

    def setRef(a: ActorRef) = ref = Some(a)

    def acknowledgeTransferStart() = ref.foreach(_ ! Ack.Started)
    def acknowledgeTransferChunk(index: Long) = ref.foreach(_ ! Ack.Chunk(index))
    def acknowledgeTransferFinish() = ref.foreach(_ ! Ack.Finished)
  }

  trait TimeoutOnStart extends Acknowledgement {
    override def acknowledgeTransferStart() = ()
  }

  trait TimeoutOnChunks extends Acknowledgement {
    val timeoutOnChunks: Set[Long]
    override def acknowledgeTransferChunk(index: Long) = if (timeoutOnChunks.contains(index)) ()
                                                         else super.acknowledgeTransferChunk(index)
  }

  trait AcknowledgeWrongChunk extends Acknowledgement {
    override def acknowledgeTransferChunk(index: Long) = super.acknowledgeTransferChunk(index + 1)
  }

  trait TimeoutOnFinish extends Acknowledgement {
    override def acknowledgeTransferFinish = ()
  }

  trait RviMock extends RviInterface with Acknowledgement {
    implicit val ec: ExecutionContext = system.dispatcher
    val msgs = scala.collection.mutable.Buffer[Message]()
    def registerService(networkAddress: URL, service: String): Future[HttpResponse] = ???
    def notify(s: Vehicle.IdentificationNumber, p: Package): Future[HttpResponse] = ???
    def transferStart(transactionId: Long, destination: String, packageIdentifier: String, byteSize: Long, chunkSize: Long, checksum: String): Future[HttpResponse] = {
      this.synchronized(msgs += TransferStart(transactionId, destination, packageIdentifier, byteSize, checksum))
      val f = Future.successful(HttpResponse())
      f.foreach(_ => {Thread.sleep(200); acknowledgeTransferStart})
      f
    }
    def transferChunk(transactionId: Long, destination: String, index: Long, msg: Array[Byte]): Future[HttpResponse] = {
      this.synchronized(msgs += TransferChunk(transactionId, destination, index, msg.mkString))
      val f = Future.successful(HttpResponse())
      f.foreach(_ => {Thread.sleep(200); acknowledgeTransferChunk(index)})
      f
    }
    def transferFinish(transactionId: Long, destination: String): Future[HttpResponse] = {
      this.synchronized(msgs += TransferFinish(transactionId))
      val f = Future.successful(HttpResponse())
      f.foreach(_ => {Thread.sleep(200); acknowledgeTransferFinish})
      f
    }
  }

  class TestFile(chunkSize: Int) {
    val contents: Array[Byte] = Stream.continually(Array.range(20, 90)).flatten.map(_.toByte).take(3000).toArray

    def persist: File = {
      val file = File.createTempFile("test", "file")
      val fileOutFile: FileOutputStream = new FileOutputStream(file)
      fileOutFile.write(contents)
      fileOutFile.close()
      file.deleteOnExit()
      file
    }

    def size: Int = contents.length

    def chunk(n: Int): Array[Byte] = contents.grouped(chunkSize).toSeq(n)
  }

  val chunkSize = 1024

  val defaultTestFile = new TestFile(chunkSize)
  val defaultTestFilePersisted = defaultTestFile.persist

  val defaultTransactionId = 123456.toLong

  def createTransfer(rviNode: RviInterface, file: File = defaultTestFilePersisted): TransferActorRef =
    TestFSMRef(new Transfer(defaultTransactionId, "/vins/123", file, "vim-2.1", "MYCHECKSUM", rviNode))

  def expectMessages(rviNode: RviMock, msgs: Seq[Message]) =
    awaitCond(rviNode.msgs == msgs, DurationInt(4).second, DurationInt(1).seconds)

  def expectTerminated(t: TransferActorRef) =
    awaitCond(t.underlying.isTerminated, DurationInt(4).second, DurationInt(1).seconds)

  "A transfer must go through the happy path" in {
    val rviNode = new RviMock { }
    val transfer = createTransfer(rviNode)
    rviNode.setRef(transfer)
    transfer ! Start

    val expected = Seq(
      TransferStart(defaultTransactionId, "/vins/123", "vim-2.1", defaultTestFile.size, "MYCHECKSUM"),
      TransferChunk(defaultTransactionId, "/vins/123", 0, defaultTestFile.chunk(0).mkString),
      TransferChunk(defaultTransactionId, "/vins/123", 1, defaultTestFile.chunk(1).mkString),
      TransferChunk(defaultTransactionId, "/vins/123", 2, defaultTestFile.chunk(2).mkString),
      TransferFinish(defaultTransactionId)
    )

    expectMessages(rviNode, expected)
    expectTerminated(transfer)
  }

  "A transfer times out when start message is not ACK'd" in {
    val rviNode = new RviMock with TimeoutOnStart
    val transfer = createTransfer(rviNode)
    rviNode.setRef(transfer)
    transfer ! Start

    expectTerminated(transfer)
  }

  "A transfer times out when a chunk message is not ACK'd" in {
    val rviNode = new RviMock with TimeoutOnChunks { val timeoutOnChunks = Set(1.toLong) }
    val transfer = createTransfer(rviNode)
    rviNode.setRef(transfer)
    transfer ! Start

    expectTerminated(transfer)
  }

  "A transfer errors when a chunk message ACK'd with a different chunk id" in {
    val rviNode = new RviMock with AcknowledgeWrongChunk
    val transfer = createTransfer(rviNode)
    rviNode.setRef(transfer)
    transfer ! Start

    expectTerminated(transfer)
  }

  "A transfer times out when a finish message is not ACK'd" in {
    val rviNode = new RviMock with TimeoutOnFinish
    val transfer = createTransfer(rviNode)
    rviNode.setRef(transfer)
    transfer ! Start

    expectTerminated(transfer)
  }
}

